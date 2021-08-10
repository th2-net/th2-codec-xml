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

import com.exactpro.sf.common.messages.structures.IDictionaryStructure
import com.exactpro.sf.common.messages.structures.IFieldStructure
import com.exactpro.sf.common.messages.structures.IMessageStructure
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants

fun NodeList.forEach(func: (Node) -> Unit) {
    for (i in 0 until length) {
        func(item(i))
    }
}

fun NodeList.toList(): List<Node> {
    val list = ArrayList<Node>(this.length)

    this.forEach {
        list.add(it)
    }
    return list
}

fun Node.getText(): String =
    nodeValue ?: childNodes.let {
        if (it.length == 0) "" else it.toList().find { node -> node.nodeType == Node.TEXT_NODE }?.nodeValue ?: ""
    }

fun Node.setText(text: String, document: Document) {
    appendChild(document.createTextNode(text))
}

fun Node.addNode(name: String, document: Document) : Node = appendChild(document.createElement(name))
fun Node.findNode(name: String) : Node? = childNodes.toList().find { it.nodeName == name }
fun Node.findOrAddNode(name: String, document: Document) : Node = findNode(name) ?: addNode(name, document)

fun IDictionaryStructure.getCharsetName(): String? = attributes[XmlPipelineCodec.XML_CHARSET_ATTRIBUTE]?.value

fun IFieldStructure.getXmlTagName(): String = attributes[XmlPipelineCodec.XML_TAG_NAME_ATTRIBUTE]?.value ?: name
fun IFieldStructure.getXPathExpression(): String? = attributes[XmlPipelineCodec.XML_X_PATH_EXPRESSION_ATTRIBUTE]?.value
fun IFieldStructure.getAttrName(): String? = attributes[XmlPipelineCodec.XML_ATTRIBUTE_NAME_ATTRIBUTE]?.value
fun IFieldStructure.isValidNode(node: Node): Boolean {
    if (node.nodeType != Node.ELEMENT_NODE) {
        return false
    }

    if (isComplex && (this as IMessageStructure).isVirtual()) {
        return fields.containsKey(node.nodeName)
    }

    for (i in 0 until node.attributes.length) {
        val attr = node.attributes.item(i)
        if (this.attributes[XmlPipelineCodec.XML_ATTRIBUTE_NAME_ATTRIBUTE]?.value == attr.nodeName
            && this.attributes[XmlPipelineCodec.XML_ATTRIBUTE_VALUE_ATTRIBUTE]?.value == attr.nodeValue
        ) {
            return true
        }
    }

    return node.nodeName == this.getXmlTagName()
}

fun IFieldStructure.isSupportEmptyTag(): Boolean = attributes[XmlPipelineCodec.XML_EMPTY_TAG_SUPPORT]?.getCastValue() ?: false
fun IMessageStructure.isEmbedded() : Boolean = attributes[XmlPipelineCodec.EMBEDDED_ATTRIBUTE]?.getCastValue() ?: false
fun IMessageStructure.isVirtual() : Boolean = attributes[XmlPipelineCodec.XML_VIRTUAL_ATTRIBUTE]?.getCastValue() ?: false

fun XPath.find(expression: String, parentNode: Node, handleError: (Exception) -> Exception): NodeList {
    try {
        return this.evaluate(expression, parentNode, XPathConstants.NODESET) as NodeList
    } catch (e: Exception) {
        throw handleError(e)
    }
}