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
package com.jpexs.decompiler.flash.tags;

import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.SWFInputStream;
import com.jpexs.decompiler.flash.SWFOutputStream;
import com.jpexs.decompiler.flash.dumpview.DumpInfoSpecialType;
import com.jpexs.decompiler.flash.helpers.ImageHelper;
import com.jpexs.decompiler.flash.tags.base.AloneTag;
import com.jpexs.decompiler.flash.tags.base.DecodedBitmapBudget;
import com.jpexs.decompiler.flash.tags.base.ImageTag;
import com.jpexs.decompiler.flash.tags.enums.ImageFormat;
import com.jpexs.decompiler.flash.types.ALPHABITMAPDATA;
import com.jpexs.decompiler.flash.types.ALPHACOLORMAPDATA;
import com.jpexs.decompiler.flash.types.BasicType;
import com.jpexs.decompiler.flash.types.annotations.Conditional;
import com.jpexs.decompiler.flash.types.annotations.EnumValue;
import com.jpexs.decompiler.flash.types.annotations.SWFType;
import com.jpexs.decompiler.flash.types.annotations.SWFVersion;
import com.jpexs.helpers.ByteArrayRange;
import com.jpexs.helpers.SerializableImage;
import java.awt.Dimension;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DefineBitsLossless2 tag - Contains a lossless compressed image with opacity.
 *
 * @author JPEXS
 */
@SWFVersion(from = 3)
public class DefineBitsLossless2Tag extends ImageTag implements AloneTag, DecodedBitmapBudget.Holder {

    public static final int ID = 36;

    public static final String NAME = "DefineBitsLossless2";

    @SWFType(BasicType.UI8)
    @EnumValue(value = FORMAT_8BIT_COLORMAPPED, text = "8-bit colormapped")
    @EnumValue(value = FORMAT_32BIT_ARGB, text = "32-bit ARGB")
    public int bitmapFormat;

    @SWFType(BasicType.UI16)
    public int bitmapWidth;

    @SWFType(BasicType.UI16)
    public int bitmapHeight;

    @SWFType(BasicType.UI8)
    @Conditional(value = "bitmapFormat", options = {FORMAT_8BIT_COLORMAPPED})
    public int bitmapColorTableSize;

    public ByteArrayRange zlibBitmapData;

    public static final int FORMAT_8BIT_COLORMAPPED = 3;

    public static final int FORMAT_32BIT_ARGB = 5;

    /**
     * Decoded pixels, or null when they have been released. Held as one object so that a reader
     * racing a release sees either all of it or none of it, never a half-cleared tag. Released
     * copies are produced again from {@link #zlibBitmapData}, which is never dropped.
     */
    private volatile Decoded decoded;

    /**
     * The decoded forms of one lossless bitmap, whichever of them this tag's format uses.
     */
    private static final class Decoded {

        final ALPHACOLORMAPDATA colorMapData;

        final ALPHABITMAPDATA bitmapData;

        final long bytes;

        Decoded(ALPHACOLORMAPDATA colorMapData, ALPHABITMAPDATA bitmapData, long bytes) {
            this.colorMapData = colorMapData;
            this.bitmapData = bitmapData;
            this.bytes = bytes;
        }
    }

    /**
     * Constructor
     *
     * @param swf SWF
     */
    public DefineBitsLossless2Tag(SWF swf) {
        this(swf, null, swf.getNextCharacterId());
    }

    public DefineBitsLossless2Tag(SWF swf, ByteArrayRange data, int characterID) {
        super(swf, ID, NAME, data);
        this.characterID = characterID;
        bitmapFormat = DefineBitsLossless2Tag.FORMAT_32BIT_ARGB;
        bitmapWidth = 1;
        bitmapHeight = 1;
        zlibBitmapData = new ByteArrayRange(createEmptyImage());
        forceWriteAsLong = true;
    }

    public DefineBitsLossless2Tag(SWFInputStream sis, ByteArrayRange data) throws IOException {
        super(sis.getSwf(), ID, NAME, data);
        readData(sis, data, 0, false, false, false);
    }

    @Override
    public final void readData(SWFInputStream sis, ByteArrayRange data, int level, boolean parallel, boolean skipUnusualTags, boolean lazy) throws IOException {
        characterID = sis.readUI16("characterID");
        bitmapFormat = sis.readUI8("bitmapFormat");
        bitmapWidth = sis.readUI16("bitmapWidth");
        bitmapHeight = sis.readUI16("bitmapHeight");
        if (bitmapFormat == FORMAT_8BIT_COLORMAPPED) {
            bitmapColorTableSize = sis.readUI8("bitmapColorTableSize");
        }

        zlibBitmapData = sis.readByteRangeEx(sis.available(), "zlibBitmapData", DumpInfoSpecialType.ZLIB_DATA, null);
    }

    /**
     * Gets data bytes
     *
     * @param sos SWF output stream
     * @throws IOException On I/O error
     */
    @Override
    public void getData(SWFOutputStream sos) throws IOException {
        sos.writeUI16(characterID);
        sos.writeUI8(bitmapFormat);
        sos.writeUI16(bitmapWidth);
        sos.writeUI16(bitmapHeight);
        if (bitmapFormat == FORMAT_8BIT_COLORMAPPED) {
            sos.writeUI8(bitmapColorTableSize);
        }
        sos.write(zlibBitmapData);
    }

    private byte[] createEmptyImage() {
        try {
            ALPHABITMAPDATA bitmapData = new ALPHABITMAPDATA();
            bitmapData.bitmapPixelData = new int[]{0xff000000};
            ByteArrayOutputStream bitmapDataOS = new ByteArrayOutputStream();
            SWFOutputStream sos = new SWFOutputStream(bitmapDataOS, getVersion(), getCharset());
            sos.writeALPHABITMAPDATA(bitmapData, FORMAT_32BIT_ARGB, 1, 1);
            ByteArrayOutputStream zlibOS = new ByteArrayOutputStream();
            SWFOutputStream sos2 = new SWFOutputStream(zlibOS, getVersion(), getCharset());
            sos2.writeBytesZlib(bitmapDataOS.toByteArray());
            return zlibOS.toByteArray();
        } catch (IOException ex) {
            Logger.getLogger(DefineBitsLossless2Tag.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;
    }

    @Override
    public void setImage(byte[] data) throws IOException {
        SerializableImage image = new SerializableImage(ImageHelper.read(data));
        ALPHABITMAPDATA bitmapData = new ALPHABITMAPDATA();
        int width = image.getWidth();
        int height = image.getHeight();
        bitmapData.bitmapPixelData = new int[width * height];
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int pos = 0; pos < pixels.length; pos++) {
            int argb = pixels[pos];
            int a = (argb >> 24) & 0xff;
            int r = (argb >> 16) & 0xff;
            int g = (argb >> 8) & 0xff;
            int b = argb & 0xff;

            r = r * a / 255;
            g = g * a / 255;
            b = b * a / 255;

            bitmapData.bitmapPixelData[pos] = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        }

        int format = FORMAT_32BIT_ARGB;
        ByteArrayOutputStream bitmapDataOS = new ByteArrayOutputStream();
        SWFOutputStream sos = new SWFOutputStream(bitmapDataOS, getVersion(), getCharset());
        sos.writeALPHABITMAPDATA(bitmapData, format, width, height);
        ByteArrayOutputStream zlibOS = new ByteArrayOutputStream();
        SWFOutputStream sos2 = new SWFOutputStream(zlibOS, getVersion(), getCharset());
        sos2.writeBytesZlib(bitmapDataOS.toByteArray());
        zlibBitmapData = new ByteArrayRange(zlibOS.toByteArray());
        bitmapFormat = format;
        bitmapWidth = width;
        bitmapHeight = height;
        releaseDecodedBitmap();
        clearCache();
        setModified(true);
    }

    public ALPHACOLORMAPDATA getColorMapData() {
        return decoded().colorMapData;
    }

    public ALPHABITMAPDATA getBitmapData() {
        return decoded().bitmapData;
    }

    /**
     * Decoded pixels, decoding them first if this tag is not holding them.
     *
     * <p>Not synchronized on purpose. Two threads arriving together both decode and one wins, which
     * is what the field did before; taking this tag's lock here instead would put a tag lock under
     * the budget's, and the budget releases other tags while evicting.</p>
     */
    private Decoded decoded() {
        Decoded current = decoded;
        if (current != null) {
            DecodedBitmapBudget.touch(this);
            return current;
        }
        DecodedBitmapBudget.reserve(decodedBitmapBytes(), this);
        current = uncompressData();
        decoded = current;
        DecodedBitmapBudget.retained(this, current.bytes);
        return current;
    }

    private Decoded uncompressData() {
        ALPHACOLORMAPDATA newColorMapData = null;
        ALPHABITMAPDATA newBitmapData = null;
        try {
            byte[] uncompressedData = SWFInputStream.uncompressByteArray(zlibBitmapData.getArray(), zlibBitmapData.getPos(), zlibBitmapData.getLength(), uncompressedSize());
            SWFInputStream sis = new SWFInputStream(swf, uncompressedData);
            if (bitmapFormat == FORMAT_8BIT_COLORMAPPED) {
                newColorMapData = sis.readALPHACOLORMAPDATA(bitmapColorTableSize, bitmapWidth, bitmapHeight, "colorMapData");
            } else if (bitmapFormat == FORMAT_32BIT_ARGB) {
                newBitmapData = sis.readALPHABITMAPDATA(bitmapFormat, bitmapWidth, bitmapHeight, "bitmapData");
            }
        } catch (IOException ex) {
            //ignored
        }
        // A failed decode is still cached, as the old decompressed flag did, so a malformed tag is
        // not decoded again on every access. It holds nothing, so the budget is not told about it.
        long bytes = newColorMapData == null && newBitmapData == null ? 0 : decodedBitmapBytes();
        return new Decoded(newColorMapData, newBitmapData, bytes);
    }

    /**
     * Decoded size this bitmap occupies, from the header alone - no decoding, and valid before it.
     *
     * @return Bytes, 0 for a format this tag does not decode
     */
    private long decodedBitmapBytes() {
        if (bitmapFormat == FORMAT_8BIT_COLORMAPPED) {
            return 4L * (bitmapColorTableSize + 1) + (long) align4(bitmapWidth) * bitmapHeight;
        }
        if (bitmapFormat == FORMAT_32BIT_ARGB) {
            return 4L * bitmapWidth * bitmapHeight;
        }
        return 0;
    }

    /**
     * Exactly what the readers below will consume, so the inflated stream is allocated once at its
     * final size instead of being grown and then copied.
     *
     * <p>Equal to the decoded size for this tag, and only for this tag: every one of its components
     * is the same width in the stream as in memory. {@link DefineBitsLosslessTag} has to compute the
     * two separately, because 15-bit pixels arrive in two bytes and colour table entries in three,
     * and both become ints.</p>
     *
     * @return Bytes, 0 when unknown
     */
    private int uncompressedSize() {
        long size = decodedBitmapBytes();
        return size <= 0 || size > Integer.MAX_VALUE ? 0 : (int) size;
    }

    private static int align4(int value) {
        return (value + 3) & ~3;
    }

    @Override
    public void releaseDecodedBitmap() {
        decoded = null;
        DecodedBitmapBudget.discard(this);
    }

    @Override
    public boolean isDecodedBitmapEdited() {
        return isModified();
    }

    @Override
    public String describeDecodedBitmap() {
        return NAME + " " + characterID + " (" + bitmapWidth + "x" + bitmapHeight + ")";
    }

    @Override
    public void clearCache() {
        super.clearCache();
        releaseDecodedBitmap();
    }

    @Override
    public ImageFormat getImageFormat() {
        return ImageFormat.PNG;
    }

    @Override
    public ImageFormat getOriginalImageFormat() {
        return ImageFormat.PNG;
    }

    @Override
    public InputStream getOriginalImageData() {
        return null;
    }

    @Override
    protected SerializableImage getImage() {
        SerializableImage bi = new SerializableImage(bitmapWidth, bitmapHeight, SerializableImage.TYPE_INT_ARGB_PRE);
        int[] pixels = ((DataBufferInt) bi.getRaster().getDataBuffer()).getData();

        ALPHACOLORMAPDATA colorMapData = null;
        ALPHABITMAPDATA bitmapData = null;
        if (bitmapFormat == FORMAT_8BIT_COLORMAPPED) {
            colorMapData = getColorMapData();
        }
        if (bitmapFormat == FORMAT_32BIT_ARGB) {
            bitmapData = getBitmapData();
        }
        int pos32aligned = 0;
        int pos = 0;
        for (int y = 0; y < bitmapHeight; y++) {
            for (int x = 0; x < bitmapWidth; x++) {
                int c = 0;
                if ((bitmapFormat == FORMAT_8BIT_COLORMAPPED)) {
                    int colorTableIndex = colorMapData.colorMapPixelData[pos32aligned] & 0xff;
                    if (colorTableIndex < colorMapData.colorTableRGB.length) {
                        c = colorMapData.colorTableRGB[colorTableIndex];
                    }
                }
                if ((bitmapFormat == FORMAT_32BIT_ARGB)) {
                    c = bitmapData.bitmapPixelData[pos];
                }

                pixels[pos] = c;
                pos32aligned++;
                pos++;
            }
            while ((pos32aligned % 4 != 0)) {
                pos32aligned++;
            }
        }

        return bi;
    }

    @Override
    public Dimension getImageDimension() {
        return new Dimension(bitmapWidth, bitmapHeight);
    }
}
