package com.jjv360.ipfsandroid

import java.nio.charset.Charset

/** Simple class to search a stream for the occurrence of a string. */
class StreamSearcher {

    /** Bytes we are searching for */
    val bytesToFind : ByteArray

    /** Amount of matched bytes so far */
    var matchedCount = 0

    /** Constructor */
    constructor(searchText : String) {

        // Store UTF8 bytes
        bytesToFind = searchText.toByteArray(Charset.forName("UTF-8"))

    }

    /** Pass in data from the stream */
    fun add(data : ByteArray) = add(data, 0, data.size)

    /** Pass in data from the stream */
    fun add(data : ByteArray, offset : Int, length : Int) : Boolean {

        // Pass in each byte
        for (i in offset until offset+length) {
            if (add(data[i]))
                return true
        }

        return false

    }

    /** Pass in data from the stream */
    fun add(byte : Byte) : Boolean {

        // Check if next character matches
        if (bytesToFind[matchedCount] == byte) {

            // Byte matches!
            matchedCount += 1

            // Check if all bytes have matched
            if (matchedCount >= bytesToFind.size) {

                // All matched! Reset counter and return true
                matchedCount = 0
                return true

            }

        } else {

            // Byte didn't match, reset counter
            matchedCount = 0

        }

        // No match yet
        return false

    }

}