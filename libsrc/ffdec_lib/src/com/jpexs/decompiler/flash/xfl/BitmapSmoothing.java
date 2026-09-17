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

import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.tags.Tag;
import com.jpexs.decompiler.flash.tags.base.ImageTag;
import com.jpexs.decompiler.flash.tags.base.ShapeTag;
import com.jpexs.decompiler.flash.types.FILLSTYLE;
import com.jpexs.decompiler.flash.types.SHAPEWITHSTYLE;
import com.jpexs.decompiler.flash.types.shaperecords.SHAPERECORD;
import com.jpexs.decompiler.flash.types.shaperecords.StyleChangeRecord;
import java.util.HashMap;
import java.util.Map;

/** The first matching fill wins, in top-level tag, initial-fill, new-fill order. */
final class BitmapSmoothing {
    static Map<Integer, Boolean> collect(SWF swf) {
        long start = System.nanoTime();
        Map<Integer, Boolean> result = new HashMap<>();
        long shapes = 0, records = 0, fills = 0;
        for (Tag tag : swf.getTags()) {
            if (!(tag instanceof ShapeTag)) continue;
            shapes++;
            SHAPEWITHSTYLE shape = ((ShapeTag) tag).getShapes();
            fills += collect(swf, result, shape.fillStyles.fillStyles);
            for (SHAPERECORD record : shape.shapeRecords) {
                records++;
                if (record instanceof StyleChangeRecord) {
                    StyleChangeRecord change = (StyleChangeRecord) record;
                    if (change.stateNewStyles) fills += collect(swf, result, change.fillStyles.fillStyles);
                }
            }
        }
        XflMetrics.log("bitmap-smoothing", start, "shapes=" + shapes + " records=" + records
                + " fills=" + fills + " bitmaps=" + result.size());
        return result;
    }

    private static int collect(SWF swf, Map<Integer, Boolean> result, FILLSTYLE[] styles) {
        for (FILLSTYLE fill : styles) {
            switch (fill.fillStyleType) {
                case FILLSTYLE.REPEATING_BITMAP:
                case FILLSTYLE.CLIPPED_BITMAP:
                case FILLSTYLE.NON_SMOOTHED_REPEATING_BITMAP:
                case FILLSTYLE.NON_SMOOTHED_CLIPPED_BITMAP:
                    // Match getNeededCharacters: ignore the sentinel and non-image references.
                    if (fill.bitmapId != 65535 && !result.containsKey(fill.bitmapId)
                            && swf.getCharacter(fill.bitmapId) instanceof ImageTag) {
                        result.put(fill.bitmapId, fill.fillStyleType == FILLSTYLE.REPEATING_BITMAP
                                || fill.fillStyleType == FILLSTYLE.CLIPPED_BITMAP);
                    }
                    break;
                default:
                    break;
            }
        }
        return styles.length;
    }
}
