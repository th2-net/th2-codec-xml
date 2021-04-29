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

class XmlPipelineCodec : IPipelineCodec {

    companion object {
        const val XML_TAG_NAME_ATTRIBUTE = "XmlTagName"
        const val XML_ATTRIBUTE_MSG_TYPE_ATTRIBUTE = "XmlMsgTypeAttribute"
        const val XML_ATTRIBUTE_MSG_TYPE_VALUE_ATTRIBUTE = "XmlMsgTypeValueAttribute"

        private val LOGGER: Logger = LoggerFactory.getLogger(XmlPipelineCodec::class.java)
        private val documentBuilderHolder: ThreadLocal<DocumentBuilder?> = ThreadLocal.withInitial {
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
    private var messageTypesFromAttr : Map<String, Map<String, IMessageStructure>> = emptyMap()

    override fun init(dictionary: IDictionaryStructure, settings: IPipelineCodecSettings?) {
        messagesTypes = dictionary.messages
        messageXmlTags = dictionary.messages.map { it.value.getXmlName() to it.value}.toMap()

        val tmp = HashMap<String, MutableMap<String, IMessageStructure>>()

        dictionary.messages.forEach { (_, msgStructure) ->
            msgStructure.attributes[XML_ATTRIBUTE_MSG_TYPE_ATTRIBUTE]?.value?.also { attrName ->
                msgStructure.attributes[XML_ATTRIBUTE_MSG_TYPE_VALUE_ATTRIBUTE]?.value?.also { attrVal ->
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
                if (it.hasMessage() && it.message.metadata.protocol == protocol)
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

    protected fun encodeOne(message: Message, messageStructure: IMessageStructure) : RawMessage {
        val document = documentBuilderHolder.get()?.newDocument()
            ?: throw EncodeException("Internal codec error. Can not create DocumentBuilder")

        encodeMessage(document, document, message, messageStructure)

        val output = ByteString.newOutput()
        writeXml(document, output)

        return RawMessage.newBuilder().apply {
            metadataBuilder.protocol = "XML"
            metadataBuilder.id = message.metadata.id
            body = output.toByteString()
        }.build()
    }

    protected fun encodeMessage(document: Document, node: Node, message: Message, messageStructure: IMessageStructure) {
        val msgType = messageStructure.name
        val msgNode = node.appendChild(document.createElement(messageStructure.getXmlName()))

        messageStructure.fields.forEach { (_, fieldStructure) ->
            try {
                encodeField(document, msgNode, fieldStructure.getXmlName(), fieldStructure, message.fieldsMap[fieldStructure.name])
            } catch (e: EncodeException) {
                throw EncodeException("Can not encode message with type '${msgType}'")
            }
        }
    }

    protected fun encodeField(document: Document, node: Node, key: String, fieldStructure: IFieldStructure, value: Value?) {
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

    protected fun encodeValue(document: Document, node: Node, key: String, fieldStructure: IFieldStructure, value: Value) {
        if (fieldStructure.isComplex) {
            if (!value.hasMessageValue() || fieldStructure !is IMessageStructure) {
                throw EncodeException("Can not encode field with name '${fieldStructure.name}'. Field must contains message value")
            }

            return encodeMessage(document, node, value.messageValue, fieldStructure)
        }

        node.appendChild(document.createElement(key)).setText(value.simpleValue, document)
    }


    override fun decode(messageGroup: MessageGroup): MessageGroup {
        val messages = messageGroup.messagesList
        if (!messages.any { it.hasRawMessage() }) {
            return messageGroup
        }

        return MessageGroup.newBuilder().addAllMessages(
            messages.map {
                if (it.hasRawMessage() && it.rawMessage.metadata.protocol == protocol)
                    AnyMessage.newBuilder().setMessage(decodeOne(it.rawMessage)).build()
                else it
            }
        ).build()
    }

    private fun decodeOne(rawMessage: RawMessage) : Message {
        try {
            ByteArrayInputStream(rawMessage.body.toByteArray()).use {
                val document = documentBuilderHolder.get()?.parse(it)
                    ?: throw DecodeException("Internal codec error. Parse error. Can not create DocumentBuilder")

                return decodeMessageNode(document.firstChild ?: throw DecodeException("Can not find message in XML"), rawMessage.metadata.id.direction, rawMessage.metadata.id.connectionId.sessionAlias)
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

    protected fun decodeMessageNode(node: Node, direction: Direction, sessionAlias: String) : Message {
        if (node.nodeType != Node.ELEMENT_NODE) {
            throw DecodeException("Can not decode message. XML tag is not message")
        }

        var messageStructure: IMessageStructure? = null

        for(i in 0 until node.attributes.length) {
            val attr = node.attributes.item(i)
            val tmp = messageTypesFromAttr[attr.nodeName]?.get(attr.getText())
            if (tmp != null) {
                messageStructure = tmp
                break;
            }
        }

        val xmlName = node.nodeName
        if (messageStructure == null) {
            messageStructure = messageXmlTags[xmlName]
        }

        messageStructure
            ?: throw DecodeException("Can not find message in dictionary with message name = $xmlName")

        return decodeMessageNode(node, direction, sessionAlias, messageStructure).build()
    }

    protected fun decodeMessageNode(node: Node, direction: Direction, sessionAlias: String, messageStructure: IMessageStructure) : Message.Builder {
        val msgType = messageStructure.name
        val messageBuilder = message(msgType, direction, sessionAlias)

        val nodeFields = HashMap<String, MutableList<Node>>()
        node.childNodes.forEach {
            if (it.nodeType == Node.ELEMENT_NODE) {
                nodeFields.computeIfAbsent(it.nodeName) {ArrayList()}.add(it)
            }
        }

        val decodedFields = ArrayList<String>()

        messageStructure.fields.forEach {
            val xmlElementName = it.value.attributes[XML_TAG_NAME_ATTRIBUTE]?.value ?: it.key
            val fieldNodes = nodeFields[xmlElementName]

            if (fieldNodes != null) {
                try {
                    messageBuilder[it.key] = decodeFieldNode(fieldNodes, direction, sessionAlias, it.value)
                    decodedFields.add(xmlElementName)
                } catch (e: DecodeException) {
                    throw DecodeException("Can not decode xml element with name '${xmlElementName}' like field with name '${it.value.name}' in message with message type '${msgType}'", e)
                }
            } else if (it.value.isRequired) {
                throw DecodeException("Can not find field with name '${it.key}' in message with type '$msgType'")
            }
        }

        if (messageStructure.fields.size > decodedFields.size && LOGGER.isWarnEnabled) {
            LOGGER.warn("Not decoded fields from incoming message with type '{}': {}", msgType, messageStructure.fields.keys.minus(decodedFields))
        }

        return messageBuilder
    }

    protected fun decodeFieldNode(nodes: List<Node>, direction: Direction, sessionAlias: String, fieldStructure: IFieldStructure) : Value {
        if (fieldStructure.isCollection) {
            val listBuilder = ListValue.newBuilder()
            nodes.forEach {
                listBuilder.add(decodeValue(it, direction, sessionAlias, fieldStructure))
            }
            return listBuilder.toValue()
        }

        if (nodes.size > 1) {
            throw DecodeException("Field with name '${fieldStructure.name}' can not decode. Have more than 1 xml tags for this field")
        }

        return decodeValue(nodes[0], direction, sessionAlias, fieldStructure)
    }

    protected fun decodeValue(node: Node, direction: Direction, sessionAlias: String, fieldStructure: IFieldStructure) : Value {
        if (fieldStructure.isComplex && fieldStructure is IMessageStructure) {
            try {
                return decodeMessageNode(node, direction, sessionAlias, fieldStructure).toValue()
            } catch (e: DecodeException) {
                throw DecodeException("Can not decode field with name '${fieldStructure.name}'", e)
            }
        }

        return decodeSimpleField(node, fieldStructure)
    }

    protected fun decodeSimpleField(node: Node, fieldStructure: IFieldStructure) : Value {
        val value = node.getText()

        if (value.isNullOrEmpty()) {
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

    private fun writeXml(document: Document, outputStream: OutputStream) {
        try {
            val transformer: Transformer = TransformerFactory.newInstance().newTransformer()
            transformer.setOutputProperty(OutputKeys.INDENT, "yes")
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
            transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, "yes")
            transformer.transform(DOMSource(document), StreamResult(outputStream))
        } catch (e: TransformerConfigurationException) {
            // This exception is unlikely to be thrown because factory has default settings
            LOGGER.error("Invalid settings of TransformerFactory", e)
        } catch (e: TransformerException) {
            LOGGER.error("An error occurred while conversion DOM to text representation", e)
        }
    }

    private fun NodeList.forEach(func: (Node) -> Unit) {
        for(i in 0 until length) {
            func(item(i))
        }
    }

    private fun IFieldStructure.getXmlName() : String = attributes[XML_TAG_NAME_ATTRIBUTE]?.value ?: name

    private fun Node.getText() : String? = nodeValue ?: childNodes.let { if (it.length == 0) null else it.item(0).nodeValue }
    private fun Node.setText(text: String, document: Document) : Unit { appendChild(document.createTextNode(text)) }
}