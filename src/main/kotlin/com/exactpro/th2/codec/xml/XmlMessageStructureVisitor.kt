/*
 * Copyright 2021-2021 Exactpro (Exactpro Systems Limited)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.codec.xml

import com.exactpro.sf.common.messages.DefaultMessageStructureVisitor
import com.exactpro.sf.common.messages.IMessage
import com.exactpro.sf.common.messages.MessageStructureWriter
import com.exactpro.sf.common.messages.structures.IFieldStructure
import com.exactpro.sf.common.messages.structures.IMessageStructure
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.message.get
import com.exactpro.th2.common.message.getList
import com.exactpro.th2.common.message.getString
import com.exactpro.th2.common.value.getList
import com.exactpro.th2.common.value.getMessage
import com.exactpro.th2.common.value.getString
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class XmlMessageStructureVisitor(private val document: Document, private val node: Node, private val message: Message, private val onGenerateField: () -> Unit = {}) :
    DefaultMessageStructureVisitor() {

    override fun visit(fieldName: String, value: String?, fldStruct: IFieldStructure, isDefault: Boolean) {
        val fieldValue = value ?: message.getString(fieldName) ?: fldStruct.getDefaultValue<String?>()
        if (fieldValue != null) {
            val attrName = fldStruct.getAttrName()
            if (attrName != null) {
                if (node is Element) {
                    node.setAttribute(attrName, fieldValue)
                    onGenerateField()
                } else {
                    error("Field node is not element. Field name = $fieldName")
                }
            } else {
                node.addNode(fldStruct.getXmlTagName(), document).setText(fieldValue, document)
                onGenerateField()
            }
        } else if (fldStruct.isRequired) {
            error("Can not find field with name = $fieldName")
        }
    }

    override fun visitStringCollection(
        fieldName: String,
        value: MutableList<String>?,
        fldStruct: IFieldStructure,
        isDefault: Boolean
    ) {
        val listValue = message.getList(fieldName)
        if (listValue == null && fldStruct.isRequired) {
            error("Can not find field with name = $fieldName")
        }

        listValue?.forEach { element ->
            element.getString()?.also { strValue ->
                node.addNode(fldStruct.getXmlTagName(), document).setText(strValue, document)
                onGenerateField()
            }
        }
    }

    override fun visit(fieldName: String, msg: IMessage?, fldStruct: IFieldStructure, isDefault: Boolean) {
        if (fldStruct !is IMessageStructure) {
            error("Can not find message structure for field with name = $fieldName")
        }

        var generated = false
        val newNode = document.createElement(fldStruct.getXmlTagName())

        if (fldStruct.isEmbedded()) {
            MessageStructureWriter.WRITER.traverse(
                XmlMessageStructureVisitor(
                    document,
                    newNode,
                    this.message
                ) { generated = true }, fldStruct
            )
        } else {
            val messageValue = this.message[fieldName]?.getMessage()
            if (messageValue == null && fldStruct.isRequired) {
                error("Can not find field with name = $fieldName")
            }

            messageValue?.also {
                MessageStructureWriter.WRITER.traverse(
                    XmlMessageStructureVisitor(
                        document,
                        document.createElement(fldStruct.getXmlTagName()),
                        messageValue
                    ) { generated = true }, fldStruct
                )
            }
        }

        if (generated) {
            node.appendChild(newNode)
            onGenerateField()
        }
    }

    override fun visitMessageCollection(
        fieldName: String,
        message: MutableList<IMessage>?,
        fldStruct: IFieldStructure,
        isDefault: Boolean
    ) {
        if (fldStruct !is IMessageStructure) {
            error("Can not find message structure for field with name = $fieldName")
        }

        val listValue = this.message[fieldName]?.getList()
        if (listValue == null && fldStruct.isRequired) {
            error("Can not find field with name = $fieldName")
        }

        listValue?.forEach {
            val messageValue = it.getMessage() ?: error("List in field with name '$fieldName' contains not message")
            if (fldStruct.isEmbedded()) {
                MessageStructureWriter.WRITER.traverse(
                    XmlMessageStructureVisitor(
                        document,
                        node,
                        messageValue
                    ) {onGenerateField()}, fldStruct
                )
            } else {
                var generated = false
                val newNode = document.createElement(fldStruct.getXmlTagName())

                MessageStructureWriter.WRITER.traverse(
                    XmlMessageStructureVisitor(
                        document,
                        newNode,
                        messageValue
                    ), fldStruct
                )

                if (generated) {
                    node.appendChild(newNode)
                    onGenerateField()
                }
            }
        }
    }
}