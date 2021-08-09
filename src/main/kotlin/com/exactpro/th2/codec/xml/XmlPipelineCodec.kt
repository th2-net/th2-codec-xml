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

import com.exactpro.sf.common.messages.MessageStructureWriter
import com.exactpro.sf.common.messages.structures.IDictionaryStructure
import com.exactpro.sf.common.messages.structures.IFieldStructure
import com.exactpro.sf.common.messages.structures.IMessageStructure
import com.exactpro.sf.externalapi.codec.impl.EncodeException
import com.exactpro.th2.codec.CodecException
import com.exactpro.th2.codec.DecodeException
import com.exactpro.th2.codec.api.IPipelineCodec
import com.exactpro.th2.codec.api.IPipelineCodecSettings
import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.ListValue
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.grpc.Value
import com.exactpro.th2.common.message.hasField
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
import javax.xml.xpath.XPathFactory

open class XmlPipelineCodec : IPipelineCodec {

    override val protocol: String = "XML"
    private var messagesTypes: Map<String, IMessageStructure> = emptyMap()
    private var xmlCharset: Charset = Charsets.UTF_8
    private var documentTypePublic: Boolean? = null
    private var documentTypeFormatStringBase: String? = null
    private var documentTypeFormatStringUrl: String? = null
    private var xmlRootTagName: String? = null

    override fun init(dictionary: IDictionaryStructure, settings: IPipelineCodecSettings?) {
        dictionary.apply {
            messagesTypes = messages
            val xmlNames = HashSet<String>()

            messages.forEach { (_, msgStructure) ->
                val xmlName = msgStructure.getXmlTagName()
                if (!xmlNames.add(xmlName)) {
                    throw CodecException("Dictionary have messages with the same xml names = $xmlName")
                }

                try {
                    checkDictionaryMessage(msgStructure)
                } catch (e: Exception) {
                    throw CodecException(
                        "Have wrong dictionary structure in message with name '${msgStructure.name}'",
                        e
                    )
                }
            }

            getCharsetName()?.also {
                try {
                    xmlCharset = Charset.forName(it)
                } catch (e: Exception) {
                    throw CodecException("Can not find charset with name = $it")
                }
            }

            with(attributes) {
                documentTypePublic = get(XML_DOCUMENT_TYPE_PUBLIC_ATTRIBUTE)?.getCastValue()
                documentTypeFormatStringBase =
                    get(XML_DOCUMENT_TYPE_BASE_FORMAT_STR_ATTRIBUTE)?.value?.let { createFormatString(it) }
                documentTypeFormatStringUrl =
                    get(XML_DOCUMENT_TYPE_URL_FORMAT_STR_ATTRIBUTE)?.value?.let { createFormatString(it) }
                xmlRootTagName = get(XML_DOCUMENT_ROOT_TAG_ATTRIBUTE)?.value
            }

            if (documentTypePublic != null && (documentTypeFormatStringUrl.isNullOrEmpty() || documentTypePublic!! && documentTypeFormatStringBase.isNullOrEmpty())) {
                throw CodecException("Wrong dictionary. Can not set DOCTYPE tag. Base string or url string is empty")
            }

            if (documentTypePublic != null && !documentTypePublic!! && !documentTypeFormatStringBase.isNullOrEmpty()) {
                LOGGER.warn("Using system DOCTYPE, but base string is set and will not be used")
            }
        }
    }

    private fun checkDictionaryMessage(msgStructure: IMessageStructure) {
        checkDictionaryMessage(HashMap(), HashMap(), msgStructure)
    }

    private fun checkDictionaryMessage(
        fieldLikeNodes: MutableMap<String, Pair<String, IMessageStructure>>,
        fieldLikeAttrs: MutableMap<String, Pair<String, IMessageStructure>>,
        messageStructure: IMessageStructure
    ) {
        messageStructure.fields.forEach { (fieldName, fieldStructure) ->
            if (fieldStructure.isComplex) {
                if ((fieldStructure as IMessageStructure).isEmbedded()) {
                    checkDictionaryMessage(fieldLikeNodes, fieldLikeAttrs, fieldStructure)
                }

                if (messageStructure.isCollection && fieldStructure.isCollection && (fieldStructure.isEmbedded() || fieldStructure.isVirtual())) {
                    error("This codec does not support embedded or virtual messages collection with inner collections field")
                }

                if (fieldStructure.isEmbedded() && fieldStructure.isVirtual()) {
                    error("This codec does not support embedded and virtual attributes in the same message")
                }
            } else {
                val attrName = fieldStructure.getAttrName()
                if (attrName == null) {
                    val prevField = fieldLikeNodes.put(fieldStructure.getXmlTagName(), fieldName to messageStructure)
                    if (prevField != null) {
                        throw IllegalStateException("Contains field duplicates by xml nodes names. Fields: '${prevField.first}' in message with name '${prevField.second.name}' and '$fieldName' in message with name '${messageStructure.referenceName ?: messageStructure.name}'")
                    }
                } else {
                    val prevField = fieldLikeAttrs.put(fieldStructure.getXmlTagName(), fieldName to messageStructure)
                    if (prevField != null) {
                        throw IllegalStateException("Contains field duplicates by xml nodes attributes names. Fields: '${prevField.first}' in message with name '${prevField.second.name}' and '$fieldName' in message with name '${messageStructure.referenceName ?: messageStructure.name}'")
                    }
                }
            }
        }
    }

    override fun encode(messageGroup: MessageGroup): MessageGroup {
        val messages = messageGroup.messagesList
        if (messages.none { it.hasMessage() }) {
            return messageGroup
        }

        return MessageGroup.newBuilder().addAllMessages(
            messages.map { anyMsg ->
                if (anyMsg.hasMessage() && anyMsg.message.metadata.protocol.let { msgProtocol -> msgProtocol.isNullOrEmpty() || msgProtocol == this.protocol })
                    AnyMessage.newBuilder().setRawMessage(encodeOne(anyMsg.message)).build()
                else anyMsg
            }
        ).build()
    }

    private fun encodeOne(message: Message): RawMessage {
        val messageStructure = messagesTypes[message.messageType]
            ?: throw EncodeException("Can not encode message. Can not find message with message type '${message.messageType}'. ${message.toJson()}")

        return encodeOne(message, messageStructure)
    }

    private fun encodeOne(message: Message, messageStructure: IMessageStructure): RawMessage {

        val document = DOCUMENT_BUILDER.get().newDocument()
        val xmlMsgType = messageStructure.getXmlTagName()

        val msgNode =
            (xmlRootTagName?.let { rootTagName -> document.addNode(rootTagName, document) } ?: document).addNode(
                xmlMsgType,
                document
            )

        try {
            MessageStructureWriter.WRITER.traverse(
                XmlMessageStructureVisitor(document, msgNode, message, false),
                messageStructure
            )
        } catch (e: IllegalStateException) {
            throw EncodeException("Can not encode message = ${message.toJson()}", e)
        }

        val output = ByteString.newOutput()
        writeXml(document, xmlMsgType, output)

        return RawMessage.newBuilder().apply {
            metadataBuilder.putAllProperties(message.metadata.propertiesMap)
            metadataBuilder.protocol = protocol
            metadataBuilder.id = message.metadata.id
            metadataBuilder.timestamp = message.metadata.timestamp
            body = output.toByteString()
        }.build()
    }


    override fun decode(messageGroup: MessageGroup): MessageGroup {
        val messages = messageGroup.messagesList
        if (messages.none { it.hasRawMessage() }) {
            return messageGroup
        }


        return MessageGroup.newBuilder().addAllMessages(
            messages.flatMap { input ->
                if (input.hasRawMessage())
                    try {
                        decodeOne(input.rawMessage).map { AnyMessage.newBuilder().setMessage(it).build() }
                    } catch (e: Exception) {
                        throw IllegalStateException("Can not decode message = ${input.rawMessage.toJson()}", e)
                    }
                else listOf(input)
            }
        ).build()
    }

    private fun decodeOne(rawMessage: RawMessage): List<Message> {
        try {
            ByteArrayInputStream(rawMessage.body.toByteArray()).use { input ->
                val document = DOCUMENT_BUILDER.get().parse(input)

                val messages = findMessageTypes(document)

                if (messages.isEmpty()) {
                    throw DecodeException("Can not find messages for decoding. ${rawMessage.toJson()}")
                }

                return messages.flatMap { (msgType, nodes) ->
                    val msgStructure: IMessageStructure = checkNotNull(messagesTypes[msgType])
                    nodes.map { xmlNode ->
                        decodeMessageNode(
                            xmlNode,
                            msgStructure
                        ).also { builder ->
                            builder.metadataBuilder.also { msgMetadata ->
                                rawMessage.metadata.also { rawMetadata ->
                                    msgMetadata.id = rawMetadata.id
                                    msgMetadata.timestamp = rawMetadata.timestamp
                                    msgMetadata.protocol = protocol
                                    msgMetadata.putAllProperties(rawMetadata.propertiesMap)
                                }
                            }

                        }.build()
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

    private fun findMessageTypes(node: Node): Map<String, List<Node>> {
        if (!node.hasChildNodes()) {
            return emptyMap()
        }

        val rootNode =
            if (node.firstChild.nodeName == xmlRootTagName) node.firstChild else node

        val result = HashMap<String, List<Node>>()
        messagesTypes.forEach { (type, msgStructure) ->
            (msgStructure.getXPathExpression()?.let {
                X_PATH.get().find(it, rootNode) { ex ->
                    DecodeException(
                        "Can not execute XPath exception for message type '$type'",
                        ex
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

    private fun decodeMessageNode(
        node: Node,
        messageStructure: IMessageStructure
    ): Message.Builder {
        val msgType = messageStructure.name
        val messageBuilder = message(msgType)

        decodeMessageFields(messageBuilder, node, messageStructure)

        return messageBuilder
    }

    private fun decodeMessageFields(
        messageBuilder: Message.Builder,
        node: Node,
        messageStructure: IMessageStructure
    ) {
        val withoutXPath = HashMap<String, IFieldStructure>()

        messageStructure.fields.forEach { (fieldName, fieldStructure) ->
            val expression = fieldStructure.getXPathExpression()
            if (expression != null) {
                val nodes = X_PATH.get().find(
                    expression,
                    node
                ) { ex ->
                    DecodeException(
                        "Can not execute XPath expression for field '$fieldName' in message with message type '${messageStructure.name}'",
                        ex
                    )
                }.toList()
                if (nodes.isNotEmpty()) {
                    decodeFieldNode(messageBuilder, fieldName, nodes, fieldStructure)
                } else if (fieldStructure.isRequired) {
                    throw DecodeException("Can not find field with name '$fieldName' in message with message type '${messageStructure.name}'")
                }
            } else {
                withoutXPath[fieldName] = fieldStructure
            }
        }

        withoutXPath.forEach { (fieldName, fieldStructure) ->
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
                decodeFieldNode(messageBuilder, fieldName, list, fieldStructure)
            } else if (fieldStructure.isRequired) {
                throw DecodeException("Can not find field with name '$fieldName' in message with message type '${messageStructure.name}'")
            }
        }
    }

    private fun decodeFieldNode(
        message: Message.Builder,
        fieldName: String,
        nodes: List<Node>,
        fieldStructure: IFieldStructure
    ) {
        if (fieldStructure.isComplex && (fieldStructure as IMessageStructure).isVirtual()) {
            if (fieldStructure.isCollection) {
                val listValue = ArrayList<Message.Builder>()
                var virtualMessage = message()
                for (node in nodes) {
                    val field = fieldStructure.fields.entries.find { it.value.isValidNode(node) }
                        ?: throw DecodeException("Can not decode xml node '$node'")
                    if (virtualMessage.hasField(field.key)) {
                        listValue.add(virtualMessage)
                        virtualMessage = message()
                    }
                    decodeFieldNode(virtualMessage, field.key, listOf(node), field.value)
                }

                if (virtualMessage.fieldsCount != 0) {
                    listValue.add(virtualMessage)
                }

                message[fieldName] = listValue
            } else {
                val virtualMessage = message()

                fieldStructure.fields.forEach { (fldName, fldStructure) ->
                    decodeFieldNode(virtualMessage, fldName, nodes.filter { fldStructure.isValidNode(it) }, fldStructure)
                }

                message[fieldName] = virtualMessage
            }
            return
        }

        if (fieldStructure.isCollection) {
            val listBuilder = ListValue.newBuilder()
            nodes.forEach {
                listBuilder.add(decodeListValue(it, fieldStructure))
            }
            message[fieldName] = listBuilder.toValue()
            return
        }

        if (nodes.size > 1) {
            throw DecodeException("Field with name '${fieldStructure.name}' can not decode. Have more than 1 xml tags for this field")
        }

        decodeValue(message, fieldName, nodes[0], fieldStructure)
    }

    private fun decodeValue(
        message: Message.Builder,
        fieldName: String,
        node: Node,
        fieldStructure: IFieldStructure
    ) {
        if (fieldStructure.isComplex && fieldStructure is IMessageStructure && fieldStructure.attributes[EMBEDDED_ATTRIBUTE]?.getCastValue<Boolean>() == true) {
            decodeMessageFields(message, node, fieldStructure)
            return
        }

        message[fieldName] = decodeListValue(node, fieldStructure)
    }

    private fun decodeListValue(
        node: Node,
        fieldStructure: IFieldStructure
    ): Value {
        if (fieldStructure.isComplex && fieldStructure is IMessageStructure) {
            try {
                val fieldValue = message()
                decodeMessageFields(fieldValue, node, fieldStructure)
                return fieldValue.toValue()
            } catch (e: DecodeException) {
                throw DecodeException("Can not decode field with name '${fieldStructure.name}'", e)
            }
        }

        return decodeSimpleField(node, fieldStructure)
    }

    private fun decodeSimpleField(
        node: Node,
        fieldStructure: IFieldStructure
    ): Value {

        val value = node.getText()

        if (value.isEmpty()) {
            throw DecodeException("Can not decode field with name '${fieldStructure.name}'. Wrong format")
        }

        if (fieldStructure.values.isNotEmpty()) {
            if (fieldStructure.values.filter { it.value.value == value }.isEmpty()) {
                throw DecodeException("Can not decode field with name '${fieldStructure.name}'. Can not find value '$value' in dictionary. Possible values: ${fieldStructure.values.map { it.value.value }}")
            }
        }

        return value.toValue()
    }

    private fun writeXml(document: Document, xmlMsgType: String, outputStream: OutputStream) {
        try {
            val transformer: Transformer = TransformerFactory.newInstance().newTransformer()
            configureTransformer(document, xmlMsgType, transformer)

            transformer.transform(DOMSource(document), StreamResult(outputStream.writer(xmlCharset)))
        } catch (e: TransformerConfigurationException) {
            // This exception is unlikely to be thrown because factory has default settings
            LOGGER.error("Invalid settings of TransformerFactory", e)
        } catch (e: TransformerException) {
            LOGGER.error("An error occurred while conversion DOM to text representation", e)
        }
    }

    private fun configureTransformer(document: Document, xmlMsgType: String, transformer: Transformer) {
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
        transformer.setOutputProperty(OutputKeys.ENCODING, xmlCharset.name())

        if (documentTypePublic != null) {
            if (documentTypePublic!!) {
                transformer.setOutputProperty(
                    OutputKeys.DOCTYPE_PUBLIC,
                    documentTypeFormatStringBase!!.format(xmlMsgType)
                )
                transformer.setOutputProperty(
                    OutputKeys.DOCTYPE_SYSTEM,
                    documentTypeFormatStringUrl!!.format(xmlMsgType)
                )
            } else {
                transformer.setOutputProperty(
                    OutputKeys.DOCTYPE_SYSTEM,
                    documentTypeFormatStringUrl!!.format(xmlMsgType)
                )
            }
        } else {
            transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, "yes")
        }
    }

    private fun createFormatString(str: String): String = str.replace("{}", "%1\$s")
        .replace(FORMAT_REPLACE_REGEX) { match -> match.groupValues.firstOrNull()?.let { "%$it\$s" } ?: "" }

    companion object {
        /**
         * Encoding for output. Added to file description
         */
        const val XML_CHARSET_ATTRIBUTE = "XmlEncoding"

        /**
         * Boolean attribute
         *
         * If true is public object else is system
         */
        const val XML_DOCUMENT_TYPE_PUBLIC_ATTRIBUTE = "XmlDocumentTypePublic"

        /**
         * String which contains registration, organization, type, name, language for tag.
         *
         * Format: Registration//Organization//Type Name//Lang
         *
         * Using **{*number*}** for formatting. **{}** equals for **{1}**
         *
         * Format arguments:
         *
         * 1. Message type
         *
         */
        const val XML_DOCUMENT_TYPE_BASE_FORMAT_STR_ATTRIBUTE = "XmlDocumentTypeBase"

        /**
         * Url to dtd file.
         *
         * Using **{*number*}** for formatting. **{}** equals for **{1}**
         *
         * Format arguments:
         *
         * 1. Message type
         *
         */
        const val XML_DOCUMENT_TYPE_URL_FORMAT_STR_ATTRIBUTE = "XmlDocumentTypeUrl"

        /**
         * Root tag name for xml file.
         */
        const val XML_DOCUMENT_ROOT_TAG_ATTRIBUTE = "XmlRootTag"

        /**
         * Xml tag name for message
         */
        const val XML_TAG_NAME_ATTRIBUTE = "XmlTagName"

        /**
         * Xml attribute name for message or field
         */
        const val XML_ATTRIBUTE_NAME_ATTRIBUTE = "XmlAttributeName"

        /**
         * Xml attribute value for message
         */
        const val XML_ATTRIBUTE_VALUE_ATTRIBUTE = "XmlAttributeValue"

        /**
         * Boolean attribute. If true fields from the current message will be moved to a parent message during decoding and packed to sub-message during encoding
         */
        const val EMBEDDED_ATTRIBUTE = "Embedded"

        /**
         * Boolean attribute. If true fields from the current message will be packed to sub-message during decoding and moved to a parent message during encoding.
         */
        const val XML_VIRTUAL_ATTRIBUTE = "Virtual"

        /**
         * Boolean attribute. If `true` - an empty tag would be generated if message is empty.
         */
        const val XML_EMPTY_TAG_SUPPORT = "XmlEmptyTagSupport";

        /**
         * XPath expression for find xml nodes. It doesn't work for encoding
         */
        const val XML_X_PATH_EXPRESSION_ATTRIBUTE = "XPath"

        private val LOGGER: Logger = LoggerFactory.getLogger(XmlPipelineCodec::class.java)
        private val FORMAT_REPLACE_REGEX = Regex("\\{([0-9]+)}")

        private val X_PATH: ThreadLocal<XPath> = ThreadLocal.withInitial {
            XPathFactory.newInstance().newXPath()
        }

        private val DOCUMENT_BUILDER: ThreadLocal<DocumentBuilder> = ThreadLocal.withInitial {
            try {
                DocumentBuilderFactory.newInstance().newDocumentBuilder()
            } catch (e: ParserConfigurationException) {
                throw CodecException("Error while initialization. Can not create DocumentBuilderFactory", e)
            }
        }
    }
}