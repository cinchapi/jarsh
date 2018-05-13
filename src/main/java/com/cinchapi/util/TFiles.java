/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2018 Cinchapi Inc.
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
package com.cinchapi.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * Yet another file based utility class :)
 * 
 * @author jnelson
 */
public final class TFiles {

    /**
     * Concatenate the content of all the {@code files} into the {@code dest}
     * file.
     * 
     * @param dest
     * @param files
     */
    // Props to http://stackoverflow.com/q/20916111/1336833
    public static void cat(String dest, String... files) {
        try {
            FileOutputStream fos = new FileOutputStream(new File(dest));
            FileChannel destChannel = fos.getChannel();
            int size = 0;
            for (String file : files) {
                FileInputStream fis = new FileInputStream(new File(file));
                FileChannel channel = fis.getChannel();
                destChannel.transferFrom(channel, size, channel.size());
                size += channel.size();
                fis.close();
                channel.close();
            }
            fos.close();
            destChannel.close();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private TFiles() {/* noop */}
}
