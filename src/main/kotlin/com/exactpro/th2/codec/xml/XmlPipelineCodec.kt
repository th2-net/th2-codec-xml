/*
 * Copyright 2020-2021 Exactpro (Exactpro Systems Limited)
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
import com.exactpro.sf.externalapi.codec.impl.EncodeException
import com.exactpro.th2.codec.DecodeException
import com.exactpro.th2.codec.api.IPipelineCodec
import com.exactpro.th2.codec.api.IPipelineCodecSettings
import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.ListValue
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.grpc.Value
import com.exactpro.th2.common.message.message
import com.exactpro.th2.common.message.messageType
import com.exactpro.th2.common.message.set
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.common.value.add
import com.exactpro.th2.common.value.toValue
import com.google.protobuf.ByteString
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerConfigurationException
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

class XmlPipelineCodec : IPipelineCodec {

    companion object {
        const val XML_CHARSET_ATTRIBUTE = "XmlEncoding"
        const val XML_DOCUMENT_TYPE_PUBLIC_FORMAT_STR_ATTRIBUTE = "XmlDocumentTypePublic"
        const val XML_DOCUMENT_TYPE_SYSTEM_FORMAT_STR_ATTRIBUTE = "XmlDocumentTypeSystem"
        const val XML_DOCUMENT_ROOT_TAG_ATTRIBUTE = "XmlRootTag"

        const val XML_TAG_NAME_ATTRIBUTE = "XmlTagName"
        const val XML_ATTRIBUTE_NAME_ATTRIBUTE = "XmlAttributeName"
        const val XML_ATTRIBUTE_VALUE_ATTRIBUTE = "XmlAttributeValue"
        const val EMBEDDED_ATTRIBUTE = "Embedded"

        const val XML_X_PATH_EXPRESSION_ATTRIBUTE = "XPath"

        private val LOGGER: Logger = LoggerFactory.getLogger(XmlPipelineCodec::class.java)
        private val FORMAT_REPLACE_REGEX = Regex("\\{([0-9]+)}")
        private val X_PATH: XPath = XPathFactory.newInstance().newXPath()
        private val DOCUMENT_BUILDER: ThreadLocal<DocumentBuilder?> = ThreadLocal.withInitial {
            try {
                DocumentBuilderFactory.newInstance().newDocumentBuilder()
            } catch (e: ParserConfigurationException) {
                /* Actually this exception unlikely to be thrown because of default settings */
                LOGGER.error("Error while initialization.", e)
                null
            }
        }
    }

    override val protocol: String = "XML"
    private var messagesTypes: Map<String, IMessageStructure> = emptyMap()
    private var messageXmlTags: Map<String, IMessageStructure> = emptyMap()
    private var messageTypesFromAttr: Map<String, Map<String, IMessageStructure>> = emptyMap()
    private var xmlCharset: Charset = Charsets.UTF_8
    private var documentTypeFormatStringPublic: String? = null
    private var documentTypeFormatStringSystem: String? = null
    private var xmlRootTagName: String? = null

    override fun init(dictionary: IDictionaryStructure, settings: IPipelineCodecSettings?) {
        messagesTypes = dictionary.messages
        messageXmlTags = dictionary.messages.map { it.value.getXmlName() to it.value }.toMap()

        dictionary.attributes[XML_CHARSET_ATTRIBUTE]?.value?.also {
            try {
                xmlCharset = Charset.forName(it)
            } catch (e: Exception) {
                LOGGER.error("Can not find charset with name $it")
            }
        }

        documentTypeFormatStringPublic =
            dictionary.attributes[XML_DOCUMENT_TYPE_PUBLIC_FORMAT_STR_ATTRIBUTE]?.value?.let { createFormatString(it) }
        documentTypeFormatStringSystem =
            dictionary.attributes[XML_DOCUMENT_TYPE_SYSTEM_FORMAT_STR_ATTRIBUTE]?.value?.let { createFormatString(it) }

        xmlRootTagName = dictionary.attributes[XML_DOCUMENT_ROOT_TAG_ATTRIBUTE]?.value

        val tmp = HashMap<String, MutableMap<String, IMessageStructure>>()

        dictionary.messages.forEach { (_, msgStructure) ->
            msgStructure.attributes[XML_ATTRIBUTE_NAME_ATTRIBUTE]?.value?.also { attrName ->
                msgStructure.attributes[XML_ATTRIBUTE_VALUE_ATTRIBUTE]?.value?.also { attrVal ->
                    tmp.computeIfAbsent(attrName) { HashMap() }[attrVal] = msgStructure
                }
            }
        }

        messageTypesFromAttr = tmp
    }

    override fun encode(messageGroup: MessageGroup): MessageGroup {
        val messages = messageGroup.messagesList
        if (!messages.any { it.hasMessage() }) {
            return messageGroup
        }

        return MessageGroup.newBuilder().addAllMessages(
            messages.map {
                if (it.hasMessage() && it.message.metadata.protocol.let { it.isNullOrEmpty() || it == this.protocol })
                    AnyMessage.newBuilder().setRawMessage(encodeOne(it.message)).build()
                else it
            }
        ).build()
    }

    protected fun encodeOne(message: Message): RawMessage {
        val messageStructure = messagesTypes[message.messageType]
            ?: throw EncodeException("Can not encode message. Can not find message with message type '${message.messageType}'. ${message.toJson()}")

        return encodeOne(message, messageStructure)
    }

    protected fun encodeOne(message: Message, messageStructure: IMessageStructure): RawMessage {

        val document = DOCUMENT_BUILDER.get()?.newDocument()
            ?: throw EncodeException("Internal codec error. Can not create DocumentBuilder")

        val xmlMsgType = encodeMessage(
            document,
            if (xmlRootTagName.isNullOrEmpty()) document else document.appendChild(document.createElement(xmlRootTagName)),
            message,
            messageStructure
        )

        val output = ByteString.newOutput()
        writeXml(document, xmlMsgType, output)

        return RawMessage.newBuilder().apply {
            metadataBuilder.protocol = "XML"
            metadataBuilder.id = message.metadata.id
            body = output.toByteString()
        }.build()
    }


    protected fun encodeMessage(
        document: Document,
        node: Node,
        message: Message,
        messageStructure: IMessageStructure
    ): String {
        val msgType = messageStructure.name
        val msgNode = node.appendChild(document.createElement(messageStructure.getXmlName()))

        messageStructure.fields.forEach { (_, fieldStructure) ->
            try {
                encodeField(
                    document,
                    msgNode,
                    fieldStructure.getXmlName(),
                    fieldStructure,
                    message.fieldsMap[fieldStructure.name]
                )
            } catch (e: EncodeException) {
                throw EncodeException("Can not encode message with type '${msgType}'")
            }
        }

        return messageStructure.getXmlName()
    }

    protected fun encodeField(
        document: Document,
        node: Node,
        key: String,
        fieldStructure: IFieldStructure,
        value: Value?
    ) {
        if (value == null) {
            if (fieldStructure.isRequired)
                throw EncodeException("Can not encode field with name '${fieldStructure.name}'. Message must contains field '${fieldStructure.name}")
            else
                return
        }

        if (fieldStructure.isCollection) {
            if (!value.hasListValue()) {
                throw EncodeException("Can not encode field with name '${fieldStructure.name}'. Field must contains list value")
            }

            value.listValue.valuesList.forEach {
                encodeValue(document, node, key, fieldStructure, it)
            }
        } else {
            encodeValue(document, node, key, fieldStructure, value)
        }
    }

    protected fun encodeValue(
        document: Document,
        node: Node,
        key: String,
        fieldStructure: IFieldStructure,
        value: Value
    ) {
        if (fieldStructure.isComplex) {
            if (!value.hasMessageValue() || fieldStructure !is IMessageStructure) {
                throw EncodeException("Can not encode field with name '${fieldStructure.name}'. Field must contains message value")
            }

            encodeMessage(document, node, value.messageValue, fieldStructure)
        } else {
            node.appendChild(document.createElement(key)).setText(value.simpleValue, document)
        }
    }


    override fun decode(messageGroup: MessageGroup): MessageGroup {
        val messages = messageGroup.messagesList
        if (!messages.any { it.hasRawMessage() }) {
            return messageGroup
        }


        return MessageGroup.newBuilder().addAllMessages(
            messages.flatMap { input ->
                if (input.hasRawMessage())
                    runCatching {
                        decodeOne(input.rawMessage).map { AnyMessage.newBuilder().setMessage(it).build() }
                    }
                        .onFailure { LOGGER.debug("Can not decode message = ${input.rawMessage.toJson()}", it) }
                        .recover { (listOf(input)) }.getOrNull()!!
                else listOf(input)
            }
        ).build()
    }

    protected fun decodeOne(rawMessage: RawMessage): List<Message> {
        try {
            ByteArrayInputStream(rawMessage.body.toByteArray()).use {
                val document = DOCUMENT_BUILDER.get()?.parse(it)
                    ?: throw DecodeException("Internal codec error. Parse error. Can not create DocumentBuilder")

                val messages = findMessageTypes(document)

                if (messages.isEmpty()) {
                    throw DecodeException("Can not find messages for decoding. ${rawMessage.toJson()}")
                }

                return messages.flatMap {
                    val msgStructure = messagesTypes[it.key]
                    it.value.map {
                        decodeMessageNode(
                            it,
                            rawMessage.metadata.id.direction,
                            rawMessage.metadata.id.connectionId.sessionAlias,
                            msgStructure!!
                        ).build()
                    }
                }
            }
        } catch (e: Exception) {
            when (e) {
                is IOException,
                is SAXException -> {
                    throw DecodeException("Can not decode message. Can not parse XML. ${rawMessage.toJson()}", e)
                }
                else -> throw e
            }
        }
    }

    protected fun findMessageTypes(node: Node): Map<String, List<Node>> {
        if (!node.hasChildNodes()) {
            return emptyMap()
        }

        val rootNode =
            if (xmlRootTagName != null && node.firstChild.nodeName == xmlRootTagName) node.firstChild else node

        val result = HashMap<String, List<Node>>()
        messagesTypes.forEach { (type, msgStructure) ->
            (msgStructure.getXPathExpression()?.let {
                X_PATH.find(it, rootNode) {
                    DecodeException(
                        "Can not execute XPath exception for message type '$type'",
                        it
                    )
                }.toList()
            } ?: rootNode.childNodes.toList().filter { msgStructure.isValidNode(it) }).also {
                if (it.isNotEmpty()) {
                    result[type] = it
                }
            }
        }

        return result
    }

    protected fun decodeMessageNode(
        node: Node,
        direction: Direction,
        sessionAlias: String,
        messageStructure: IMessageStructure
    ): Message.Builder {
        val msgType = messageStructure.name
        val messageBuilder = message(msgType, direction, sessionAlias)
        messageBuilder.metadataBuilder.protocol = this.protocol

        decodeMessageFields(messageBuilder, node, direction, sessionAlias, messageStructure)

        return messageBuilder
    }

    protected fun decodeMessageFields(
        messageBuilder: Message.Builder,
        node: Node,
        direction: Direction,
        sessionAlias: String,
        messageStructure: IMessageStructure
    ) {
        val notFoundFields = HashSet<String>()

        messageStructure.fields.forEach { (fieldName, fieldStructure) ->
            val expression = fieldStructure.getXPathExpression()
            if (expression != null) {
                val nodes = X_PATH.find(
                    expression,
                    node
                ) { DecodeException("Can not execute XPath expression for field '$fieldName' in message with message type '${messageStructure.name}'") }
                    .toList()
                if (nodes.isNotEmpty()) {
                    decodeFieldNode(messageBuilder, fieldName, nodes, direction, sessionAlias, fieldStructure)
                    return@forEach
                }
            }

            notFoundFields.add(fieldName)
        }

        notFoundFields.forEach { fieldName ->
            val fieldStructure = messageStructure.fields[fieldName]!!
            val attrName = fieldStructure.getAttrName()
            val list = ArrayList<Node>()

            attrName?.let { node.attributes?.getNamedItem(it) }?.also {
                list.add(it)
            }

                node.childNodes.forEach { child ->
                    if (fieldStructure.isValidNode(child)) {
                        list.add(child)
                    }

                    attrName?.let { child.attributes?.getNamedItem(it) }?.also {
                        list.add(it)
                    }
                }

            if (list.isNotEmpty()) {
                decodeFieldNode(messageBuilder, fieldName, list, direction, sessionAlias, fieldStructure)
            } else if (fieldStructure.isSimple && fieldStructure.getDefaultValue<Any?>() != null) {
                val defaultValue: String? = fieldStructure.getDefaultValue()
                messageBuilder[fieldName] = defaultValue
            } else if (fieldStructure.isRequired) {
                throw DecodeException("Can not find field with name '$fieldName' in message with message type '${messageStructure.name}'")
            }

        }

    }

    protected fun decodeFieldNode(
        message: Message.Builder,
        fieldName: String,
        nodes: List<Node>,
        direction: Direction,
        sessionAlias: String,
        fieldStructure: IFieldStructure
    ) {
        if (fieldStructure.isCollection) {
            val listBuilder = ListValue.newBuilder()
            nodes.forEach {
                listBuilder.add(decodeListValue(it, direction, sessionAlias, fieldStructure))
            }
            message[fieldName] = listBuilder.toValue()
            return
        }

        if (nodes.size > 1) {
            throw DecodeException("Field with name '${fieldStructure.name}' can not decode. Have more than 1 xml tags for this field")
        }

        decodeValue(message, fieldName, nodes[0], direction, sessionAlias, fieldStructure)
    }

    protected fun decodeValue(
        message: Message.Builder,
        fieldName: String,
        node: Node,
        direction: Direction,
        sessionAlias: String,
        fieldStructure: IFieldStructure
    ) {
        if (fieldStructure.isComplex && fieldStructure is IMessageStructure && fieldStructure.attributes[EMBEDDED_ATTRIBUTE]?.getCastValue<Boolean>() == true) {
            decodeMessageFields(message, node, direction, sessionAlias, fieldStructure)
            return
        }

        message[fieldName] = decodeListValue(node, direction, sessionAlias, fieldStructure)
    }

    protected fun decodeListValue(
        node: Node,
        direction: Direction,
        sessionAlias: String,
        fieldStructure: IFieldStructure
    ): Value {
        if (fieldStructure.isComplex && fieldStructure is IMessageStructure) {
            try {
                return decodeMessageNode(node, direction, sessionAlias, fieldStructure).toValue()
            } catch (e: DecodeException) {
                throw DecodeException("Can not decode field with name '${fieldStructure.name}'", e)
            }
        }

        return decodeSimpleField(node, fieldStructure)
    }

    protected fun decodeSimpleField(
        node: Node,
        fieldStructure: IFieldStructure
    ): Value {

        val value = node.getText()

        if (value.isEmpty()) {
            throw DecodeException("Can not decode field with name '${fieldStructure.name}'. Wrong format")
        }

        if (fieldStructure.values.isNotEmpty()) {
            if (fieldStructure.values.filter { it.value.value == value }.isEmpty()) {
                throw DecodeException("Can not decode field with name '${fieldStructure.name}'. Can not find value '$value' in dictionary")
            }
        }

        return value.toValue()
    }

    override fun close() {
        super.close()
    }

    protected fun writeXml(document: Document, xmlMsgType: String, outputStream: OutputStream) {
        try {
            val transformer: Transformer = TransformerFactory.newInstance().newTransformer()
            configureTransformer(document, xmlMsgType, transformer)

            transformer.transform(DOMSource(document), StreamResult(outputStream))
        } catch (e: TransformerConfigurationException) {
            // This exception is unlikely to be thrown because factory has default settings
            LOGGER.error("Invalid settings of TransformerFactory", e)
        } catch (e: TransformerException) {
            LOGGER.error("An error occurred while conversion DOM to text representation", e)
        }
    }

    protected fun configureTransformer(document: Document, xmlMsgType: String, transformer: Transformer) {
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
        transformer.setOutputProperty(OutputKeys.ENCODING, xmlCharset.name())

        if (documentTypeFormatStringPublic != null && documentTypeFormatStringSystem != null) {
            val documentType = document.implementation.createDocumentType(
                "doctype",
                documentTypeFormatStringPublic!!.format(xmlMsgType),
                documentTypeFormatStringSystem!!.format(xmlMsgType)
            )

            transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, documentType.publicId)
            transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, documentType.systemId)
        } else {
            transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, "yes")
        }
    }

    private fun createFormatString(str: String): String = str.replace("{}", "%1\$s")
        .replace(FORMAT_REPLACE_REGEX) { match -> match.groupValues.firstOrNull()?.let { "%$it\$s" } ?: "" }

    private fun NodeList.forEach(func: (Node) -> Unit) {
        for (i in 0 until length) {
            func(item(i))
        }
    }

    private fun NodeList.toList(): List<Node> {
        val list = ArrayList<Node>(this.length)

        this.forEach {
            list.add(it)
        }
        return list
    }

    private fun IFieldStructure.getXmlName(): String = attributes[XML_TAG_NAME_ATTRIBUTE]?.value ?: name
    private fun IFieldStructure.getXPathExpression(): String? = attributes[XML_X_PATH_EXPRESSION_ATTRIBUTE]?.value
    private fun IFieldStructure.getAttrName(): String? = attributes[XML_ATTRIBUTE_NAME_ATTRIBUTE]?.value
    private fun IFieldStructure.isValidNode(node: Node): Boolean {
        if (node.nodeType != Node.ELEMENT_NODE) {
            return false
        }

        for (i in 0 until node.attributes.length) {
            val attr = node.attributes.item(i)
            if (this.attributes[XML_ATTRIBUTE_NAME_ATTRIBUTE]?.value == attr.nodeName
                && this.attributes[XML_ATTRIBUTE_VALUE_ATTRIBUTE]?.value == attr.nodeValue
            ) {
                return true
            }
        }

        return node.nodeName == this.getXmlName()
    }

    private fun Node.getText(): String =
        nodeValue ?: childNodes.let { if (it.length == 0) "" else it.item(0).nodeValue }

    private fun Node.setText(text: String, document: Document): Unit {
        appendChild(document.createTextNode(text))
    }

    private fun XPath.find(expression: String, parentNode: Node, handleError: (Exception) -> Exception): NodeList {
        try {
            return this.evaluate(expression, parentNode, XPathConstants.NODESET) as NodeList
        } catch (e: Exception) {
            throw handleError(e)
        }
    }
}