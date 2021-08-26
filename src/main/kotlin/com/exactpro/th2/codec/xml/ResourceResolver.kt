@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")

package com.exactpro.th2.codec.xml

import com.sun.org.apache.xerces.internal.dom.DOMInputImpl
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.util.*

/*override fun resolveResource(
        type: String,
        namespaceURI: String,
        publicId: String,
        systemId: String,
        baseURI: String
    ): LSInput {
        // note: in this sample, the XSD's are expected to be in the root of the classpath
        val resourceAsStream = this.javaClass.classLoader
            .getResourceAsStream(buildPath(systemId))
        Objects.requireNonNull(resourceAsStream, String.format("Could not find the specified xsd file: %s", systemId))
        return DOMInputImpl(publicId, systemId, baseURI, resourceAsStream, "UTF-8")
    }

    private fun buildPath(systemId: String): String {
        return if (basePath == null) systemId else String.format("%s/%s", basePath, systemId)
    }
*/


class ResourceResolver() : LSResourceResolver {
    override fun resolveResource(
        type: String?, namespaceURI: String?,
        publicId: String?, systemId: String?, baseURI: String?
    ): LSInput? {

        // note: in this sample, the XSD's are expected to be in the root of the classpath
        val resourceAsStream = this.javaClass.classLoader
            .getResourceAsStream(systemId)
        return Input(publicId!!, systemId!!, resourceAsStream)
    }
}


class Input(private var publicId: String, private var systemId: String, input: InputStream?) : LSInput {
    override fun getPublicId(): String {
        return publicId
    }

    override fun setPublicId(publicId: String) {
        this.publicId = publicId
    }

    override fun getBaseURI(): String? {
        return null
    }

    override fun getByteStream(): InputStream? {
        return null
    }

    override fun getCertifiedText(): Boolean {
        return false
    }

    override fun getCharacterStream(): Reader? {
        return null
    }

    override fun getEncoding(): String? {
        return null
    }

    override fun getStringData(): String? {
        synchronized(inputStream) {
            try {
                val input = ByteArray(inputStream.available())
                inputStream.read(input)
                return String(input)
            } catch (e: IOException) {
                e.printStackTrace()
                println("Exception $e")
                return null
            }
        }
    }

    override fun setBaseURI(baseURI: String) {}
    override fun setByteStream(byteStream: InputStream) {}
    override fun setCertifiedText(certifiedText: Boolean) {}
    override fun setCharacterStream(characterStream: Reader?) {}
    override fun setEncoding(encoding: String) {}
    override fun setStringData(stringData: String) {}
    override fun getSystemId(): String {
        return systemId
    }

    override fun setSystemId(systemId: String) {
        this.systemId = systemId
    }

    var inputStream: BufferedInputStream

    init {
        inputStream = BufferedInputStream(input)
    }
}