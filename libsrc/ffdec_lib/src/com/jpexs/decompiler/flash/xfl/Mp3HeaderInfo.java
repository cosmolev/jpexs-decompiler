/*
 *  Copyright (C) 2010-2026 JPEXS, All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package com.jpexs.decompiler.flash.xfl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Header;

/** Read headers without decoding or retaining frames or PCM buffers. */
final class Mp3HeaderInfo {
    final int seekSamples;
    int bitRate;
    int frames;

    Mp3HeaderInfo(byte[] data) throws IOException {
        if (data.length < 2) throw new IOException("Missing MP3 seek samples");
        seekSamples = (short) ((data[0] & 255) | ((data[1] & 255) << 8));
        Bitstream stream = new Bitstream(new ByteArrayInputStream(data, 2, data.length - 2));
        try {
            Header header;
            while ((header = stream.readFrame()) != null) {
                // MP3FRAME retains JLayer's mutable Header. The legacy frames.get(0)
                // therefore observes the LAST header's bitrate, including for VBR.
                bitRate = header.bitrate();
                frames++;
                stream.closeFrame();
            }
        } catch (BitstreamException ex) {
            throw new IOException("Invalid MP3 frame header", ex);
        } finally {
            try { stream.close(); } catch (BitstreamException ex) { /* memory input */ }
        }
    }
}
