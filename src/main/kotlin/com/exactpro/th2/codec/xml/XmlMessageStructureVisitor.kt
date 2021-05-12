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

class XmlMessageStructureVisitor(private val document: Document, private val node: Node, private val message: Message) :
    DefaultMessageStructureVisitor() {

    override fun visit(fieldName: String, value: String?, fldStruct: IFieldStructure, isDefault: Boolean) {
        val fieldValue = value ?: message.getString(fieldName) ?: fldStruct.getDefaultValue<String?>()
        if (fieldValue != null) {
            val attrName = fldStruct.getAttrName()
            if (attrName != null) {
                if (node is Element) {
                    node.setAttribute(attrName, fieldValue)
                } else {
                    error("Field node is not element. Field name = $fieldName")
                }
            } else {
                node.addNode(fldStruct.getXmlName(), document).setText(fieldValue, document)
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
                node.addNode(fldStruct.getXmlName(), document).setText(strValue, document)
            }
        }
    }

    override fun visit(fieldName: String, msg: IMessage?, fldStruct: IFieldStructure, isDefault: Boolean) {
        if (fldStruct !is IMessageStructure) {
            error("Can not find message structure for field with name = $fieldName")
        }

        if (fldStruct.isEmbedded()) {
            MessageStructureWriter.WRITER.traverse(
                XmlMessageStructureVisitor(
                    document,
                    node.appendChild(document.createElement(fldStruct.getXmlName())),
                    this.message
                ), fldStruct
            )
            return
        }

        val messageValue = this.message[fieldName]?.getMessage()
        if (messageValue == null && fldStruct.isRequired) {
            error("Can not find field with name = $fieldName")
        }

        messageValue?.also {
            MessageStructureWriter.WRITER.traverse(
                XmlMessageStructureVisitor(
                    document,
                    node.appendChild(document.createElement(fldStruct.getXmlName())),
                    messageValue
                ), fldStruct
            )
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
            MessageStructureWriter.WRITER.traverse(
                XmlMessageStructureVisitor(
                    document,
                    node.appendChild(document.createElement(fldStruct.getXmlName())),
                    messageValue
                ), fldStruct
            )
        }
    }
}