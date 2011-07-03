/*
 * Copyright (c) 2011 NullPointer Software
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package chaining;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;
 
/**
 * Very simple applet for chaining data to and from a card.
 * 
 * @author shane
 */
public class ChainingApplet extends Applet {
    private final static byte INS_GET_RESPONSE = (byte) 0xC0;
    private final static byte INS_PUT_DATA = 0x00;
    private final static byte INS_GET_DATA = 0x01;
 
    private final static byte OFFSET_SENT = 0x00;
    private final static byte OFFSET_RECV = 0x01;
    private static short[] offset;
 
    private static byte[] fileBuffer;
    private static short fileSize = 0;
    private final static short FILE_SIZE = 2048; // 2KB file
 
    private final static short MAX_APDU = 255;
 
    /**
     * Default constructor that initialises all memory to be used by the applet.
     */
    public ChainingApplet() {
        offset = JCSystem.makeTransientShortArray((short) 2, JCSystem.CLEAR_ON_RESET);
        fileBuffer = new byte[FILE_SIZE];
    }
 
    /**
     * {@inheritDoc}
     */
    public static void install(byte[] bArray, short bOffset, byte bLength) {
        // GP-compliant JavaCard applet registration
        new ChainingApplet().register(bArray, (short) (bOffset + 1), bArray[bOffset]);
    }
 
    /**
     * {@inheritDoc}
     */
    public void process(APDU apdu) {
        // Good practice: Return 9000 on SELECT
        if (selectingApplet()) {
            return;
        }
 
        short len = apdu.setIncomingAndReceive(); // This is the amount of data read from the OS.
        byte[] buf = apdu.getBuffer();
        byte cla = buf[ISO7816.OFFSET_CLA];
        byte ins = buf[ISO7816.OFFSET_INS];
        short lc = (short) (buf[ISO7816.OFFSET_LC] & 0x00ff); // this is the LC from the APDU.
 
        // get all bytes from the buffer
        while (len < lc) {
            len += apdu.receiveBytes(len);
        }
 
        // validate the INS byte (basic check that tests for valid GET-RESPONSE or GET-DATA)
        if (offset[OFFSET_SENT] > 0 && ins != INS_GET_RESPONSE) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        } else if (ins == INS_GET_RESPONSE && offset[OFFSET_SENT] == 0) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
 
        switch (buf[ISO7816.OFFSET_INS]) {
            case INS_PUT_DATA:
                saveData(buf, ISO7816.OFFSET_CDATA, offset[OFFSET_RECV], len);
 
                if ((cla & 0x10) != 0x00) {
                    offset[OFFSET_RECV] += len;
                } else {
                    // last command in the chain
                    fileSize = (short) (offset[OFFSET_RECV] + len);
                    offset[OFFSET_RECV] = 0;
                }
                break;
 
            case INS_GET_DATA:
            case INS_GET_RESPONSE:
                sendData(apdu);
                break;
 
            default:
                // good practice: If you don't know the INStruction, say so:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }
 
    /**
     * Simple method to save the data somewhere. Add real implementation here.
     * 
     * @param source
     *            buffer containing data to copy
     * @param sourceOff
     *            offset into source buffer to the data
     * @param destOff
     *            offset into the destination buffer
     * @param len
     *            length of the data to copy.
     */
    private static void saveData(byte[] source, short sourceOff, short destOff, short len) {
        // prevent buffer overflow
        if ((short) (destOff + len) > FILE_SIZE) {
            ISOException.throwIt(ISO7816.SW_FILE_FULL);
        }
 
        Util.arrayCopy(source, sourceOff, fileBuffer, destOff, len);
    }
 
    /**
     * Simple method to send chainined data to the client application (if required).
     * 
     * @param apdu
     *            current APDU
     */
    private void sendData(APDU apdu) {
        // work out how many bytes to send this time and how many will be left
        short remain = (short) (fileSize - offset[OFFSET_SENT]);
        boolean chain = remain > MAX_APDU;
        short sendLen = chain ? MAX_APDU : remain;
 
        // Get ready to send
        apdu.setOutgoing();
        apdu.setOutgoingLength(sendLen);
        apdu.sendBytesLong(fileBuffer, offset[OFFSET_SENT], sendLen);
 
        // Check to see if there are more APDU's to send
        if (chain) {
            offset[OFFSET_SENT] += sendLen; // count the bytes sent
            ISOException.throwIt(ISO7816.SW_BYTES_REMAINING_00); // indicate there are more bytes to come
        } else {
            offset[OFFSET_SENT] = 0; // no more bytes to send
        }
    }
 
}