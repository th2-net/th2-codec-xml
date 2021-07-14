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
import com.exactpro.sf.common.messages.structures.loaders.XmlDictionaryStructureLoader
import com.exactpro.th2.codec.CodecException
import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.grpc.Value
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.addFields
import com.exactpro.th2.common.message.message
import com.exactpro.th2.common.value.get
import com.google.protobuf.ByteString
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

class XmlPipelineCodecTest {

    @Test
    fun `test wrong dictionary`() {
        try {
            XmlPipelineCodec().init(
                XmlDictionaryStructureLoader().load(
                    Thread.currentThread().contextClassLoader.getResourceAsStream(
                        "test_wrong_dictionary.xml"
                    )
                )
            )
            fail()
        } catch (e: CodecException) {
            assertEquals("Have wrong dictionary structure in message with name 'TestMessage'", e.message)
        }
    }

    @Test
    fun `test common fields a`() {
        checkDecode("""
            <commonFieldsA>
                <f>123</f>
                <abc>
                    <ab>
                        <a>345</a>
                        <b>678</b>
                    </ab>
                    <c>90</c>
                </abc>
            </commonFieldsA>
        """.trimIndent(), message("CommonFieldsA").apply {
            metadataBuilder.protocol = "XML"
            addFields(
                "f", 123,
                "a", 345,
                "b", 678,
                "c", 90
            )
        })
    }

    @Test
    fun `test attributes fields`() {
        checkDecode(
            """<TestAttrMessage>
                <b>
                    <c>
                        <d>
                            <e f="123" g="1">asd</e>
                        </d>
                    </c>
                </b>
                <b>
                    <c>
                        <d>
                            <e f="456" g="2" n="48">fgh</e>
                            <h>A</h>
                        </d>
                    </c>
                </b>
                <b>
                    <c>
                        <d>
                            <e f="789" g="3">fgh</e>
                        </d>
                    </c>
                </b>
            </TestAttrMessage>
        """.trimIndent(), message("TestAttrMessage").addFields(
                "b", listOf(
                    message().addFields("F", 123, "G", 1, "e", "asd"),
                    message().addFields("F", 456, "G", 2, "e", "fgh", "n", 48, "h", "A"),
                    message().addFields("F", 789, "G", 3, "e", "fgh")
                )
            )
        )
    }

    @Test
    fun `test repeating group`() {
        checkDecode(
            """
            <repeatingGroups>
                <groupA>
                    <B>
                        <C>
                            <D>
                                <groupE>
                                    <groupF>
                                        <G>
                                            <H>1</H>
                                        </G>
                                    </groupF>
                                </groupE>
                                <groupE>
                                    <groupF>
                                        <G>
                                            <H>2</H>
                                        </G>
                                    </groupF>
                                    <groupF>
                                        <G>
                                            <H>3</H>
                                        </G>
                                    </groupF>
                                </groupE>
                            </D>
                        </C>
                    </B>
                </groupA>
                <groupA>
                    <B>
                        <C>
                            <D>
                                <groupE>
                                    <groupF>
                                        <G>
                                            <H>4</H>
                                        </G>
                                    </groupF>
                                </groupE>
                                <groupE>
                                    <groupF>
                                        <G>
                                            <H>5</H>
                                        </G>
                                    </groupF>
                                </groupE>
                                <groupE>
                                    <groupF>
                                        <G>
                                            <H>6</H>
                                        </G>
                                    </groupF>
                                    <groupF>
                                        <G>
                                            <H>7</H>
                                        </G>
                                    </groupF>
                                    <groupF>
                                        <G>
                                            <H>8</H>
                                        </G>
                                    </groupF>
                                </groupE>
                            </D>
                        </C>
                    </B>
                </groupA>
            </repeatingGroups>
        """.trimIndent(), message("RepeatingGroup").addFields(
                "A", listOf(
                    message().addFields(
                        "E", listOf(
                            message().addFields(
                                "F", listOf(
                                    message().addFields("H", 1)
                                )
                            ),
                            message().addFields(
                                "F", listOf(
                                    message().addFields("H", 2),
                                    message().addFields("H", 3)
                                )
                            )
                        )
                    ),
                    message().addFields(
                        "E", listOf(
                            message().addFields(
                                "F", listOf(
                                    message().addFields("H", 4)
                                )
                            ),
                            message().addFields(
                                "F", listOf(
                                    message().addFields("H", 5)
                                )
                            ),
                            message().addFields(
                                "F", listOf(
                                    message().addFields("H", 6),
                                    message().addFields("H", 7),
                                    message().addFields("H", 8)
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `test simple repeating`() {
        checkDecode(
            """
            <simpleRepeating>
                <user id="1">admin</user>
                <user id="2">user</user>
                <user id="3">guest</user>
            </simpleRepeating>
        """.trimIndent(), message("SimpleRepeating").addFields(
                "user", listOf(
                    message().addFields("id", 1, "name", "admin"),
                    message().addFields("id", 2, "name", "user"),
                    message().addFields("id", 3, "name", "guest")
                )
            )
        )
    }

    @Test
    fun `test decode attrs in different places`() {
        checkDecode(ATTRS_IN_DIFFERENT_PLACE_DECODE_STR, ATTRS_IN_DIFFERENT_PLACE_MSG)
    }

    @Test
    fun `test encode attrs in different place`() {
        checkEncode(ATTRS_IN_DIFFERENT_PLACE_ENCODE_STR, ATTRS_IN_DIFFERENT_PLACE_MSG)
    }

    @Test
    fun `test decode embedded`() {
        checkDecode(EMBEDDED_XML, EMBEDDED_MSG)
    }

    @Test
    fun `test encode embedded`() {
        checkEncode(EMBEDDED_XML, EMBEDDED_MSG)
    }

    @Test
    fun `test decode virtual`() {
        checkDecode(VIRTUAL_STR, VIRTUAL_MSG)
    }

    @Test
    fun `test encode virtual`() {
        checkEncode(VIRTUAL_STR, VIRTUAL_MSG)
    }

    @Test
    fun `test decode duplicate field in virtual`() {
        checkDecode(DUPLICATE_FIELD_IN_VIRTUAL_STR, DUPLICATE_FIELD_IN_VIRTUAL_MSG)
    }

    @Test
    fun `test encode duplicate field in virtual`() {
        checkEncode(DUPLICATE_FIELD_IN_VIRTUAL_STR, DUPLICATE_FIELD_IN_VIRTUAL_MSG)
    }

    @Test
    fun `test decode collection`() {
        checkDecode(COLLECTIONS_STR, COLLECTIONS_MSG)
    }

    @Test
    fun `test encode collection`() {
        checkEncode(COLLECTIONS_STR, COLLECTIONS_MSG)
    }

    private fun checkEncode(xml: String, message: Message.Builder) {
        val group =
            codec.encode(MessageGroup.newBuilder().addMessages(AnyMessage.newBuilder().setMessage(message)).build())
        assertEquals(1, group.messagesCount)

        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n$xml\n",
            group.messagesList[0].rawMessage.body.toStringUtf8()
        )
    }

    private fun checkDecode(xml: String, message: Message.Builder) {
        val group = codec.decode(createRawMessage(xml))
        assertEquals(1, group.messagesCount)

        assertEqualsMessages(message.build(), group.messagesList[0].message, true)
    }

    private fun assertEqualsMessages(expected: Message, actual: Message, checkMetadata: Boolean = false) {
        if (checkMetadata) {
            assertEquals(expected.metadata.messageType, actual.metadata.messageType, "Not equals message types")
            //assertEquals(expected.metadata.protocol, actual.metadata.protocol, "Not equals protocols")
            //assertEquals(expected.metadata.id, actual.metadata.id, "Not equals ids")
        }

        assertEquals(
            expected.fieldsCount, actual.fieldsCount,
            "Wrong count fields in actual message"
        )

        expected.fieldsMap.forEach { (fieldName, expectedValue) ->
            val actualValue = actual.fieldsMap[fieldName]
            assertNotNull(actualValue, "Field with name '$fieldName' shouldn't be null")
            try {
                assertEqualsValue(expectedValue, actualValue, checkMetadata)
            } catch (e: AssertionFailedError) {
                throw AssertionFailedError(
                    "Error in field with name '$fieldName'.\n${e.message}",
                    e.expected,
                    e.actual,
                    e.cause
                )
            }
        }
    }

    private fun assertEqualsValue(expected: Value, actual: Value, checkMetadata: Boolean = false) {
        assertEquals(expected.kindCase, actual.kindCase, "Different value types")

        when (actual.kindCase) {
            Value.KindCase.SIMPLE_VALUE -> assertEquals(expected.simpleValue, actual.simpleValue)
            Value.KindCase.LIST_VALUE -> {
                assertEquals(
                    expected.listValue.valuesCount,
                    actual.listValue.valuesCount,
                    "Wrong count of element in value"
                )
                expected.listValue.valuesList?.forEachIndexed { i, it -> assertEqualsValue(it, actual.listValue[i]) }
            }
            Value.KindCase.MESSAGE_VALUE ->
                assertEqualsMessages(expected.messageValue, actual.messageValue, checkMetadata)
            else -> error("Unknown value type")
        }
    }


    private fun createRawMessage(xml: String): MessageGroup = MessageGroup
        .newBuilder()
        .addMessages(AnyMessage
            .newBuilder()
            .setRawMessage(RawMessage
                .newBuilder().apply {
                    metadataBuilder.protocol = "XML"
                    metadataBuilder.idBuilder.connectionIdBuilder.sessionAlias = "test_session_alias"
                    body = ByteString.copyFromUtf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n$xml")
                }
            )
        )
        .build()

    companion object {
        val codec = XmlPipelineCodec()
        val dictionary: IDictionaryStructure =
            XmlDictionaryStructureLoader().load(Thread.currentThread().contextClassLoader.getResourceAsStream("test_dictionary.xml"))

        init {
            codec.init(dictionary, null)
        }

        val EMBEDDED_XML = """
            <TestEmbedded>
                <not_embedded>123</not_embedded>
                <message>
                    <embedded>456</embedded>
                </message>
            </TestEmbedded>
        """.trimIndent()

        val EMBEDDED_MSG = message("TestEmbedded").addFields(
            "not_embedded", 123,
            "embedded", 456
        )

        val VIRTUAL_STR = """
            <TestVirtual>
                <field0>123</field0>
                <field1>155</field1>
            </TestVirtual>
        """.trimIndent()

        val VIRTUAL_MSG = message("TestVirtual").addFields(
            "field0", 123,
            "virtual", message().addField("field1", 155)
        )

        val ATTRS_IN_DIFFERENT_PLACE_DECODE_STR = """
            <attributes defaultMsgAttrA="123" msgAttrA="45" msgAttrB="67">
                <commonWithAttrs commonAttrA="54" commonAttrB="76">abc</commonWithAttrs>
                <withAttrs defaultFieldAttrA="456" fieldAttrA="10" fieldAttrB="30">def</withAttrs>
            </attributes>
        """.trimIndent()

        val ATTRS_IN_DIFFERENT_PLACE_ENCODE_STR = """
            <attributes defaultMsgAttrA="123" msgAttrA="45" msgAttrB="67">
                <commonWithAttrs commonAttrA="54" commonAttrB="76">abc</commonWithAttrs>
                <withAttrs fieldAttrA="10" fieldAttrB="30">def</withAttrs>
            </attributes>
        """.trimIndent()


        val ATTRS_IN_DIFFERENT_PLACE_MSG = message("Attributes").addFields(
            "msgAttrA", 45,
            "msgAttrB", 67,
            "commonAttrA", 54,
            "commonAttrB", 76,
            "fieldAttrA", 10,
            "fieldAttrB", 30,
            "commonWithAttrs", "abc",
            "withAttrs", "def",
            "defaultMsgAttrA", 123
        )

        val DUPLICATE_FIELD_IN_VIRTUAL_STR = """
            <TestDuplicateVirtual>
                <field0>1234</field0>
            </TestDuplicateVirtual>
        """.trimIndent()

        val DUPLICATE_FIELD_IN_VIRTUAL_MSG = message("TestDuplicateVirtual")
            .addFields("field0", 1234, "virtual", message().addFields("field0", 1234))

        val COLLECTIONS_STR = """
            <TestCollection>
                <collection>1234</collection>
                <collection>5678</collection>
                <collectionMessage>
                    <field0>1011</field0>
                </collectionMessage>
                <collectionMessage>
                    <field0>1213</field0>
                </collectionMessage>
            </TestCollection>
        """.trimIndent()

        val COLLECTIONS_MSG = message("TestCollection").addFields("collection", listOf(1234, 5678),
            "collectionMessage", listOf(
                message().addField("field0", 1011),
                message().addField("field0", 1213)
            ))

    }
}