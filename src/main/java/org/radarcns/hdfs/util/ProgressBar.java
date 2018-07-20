/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.hdfs.util;

/**
 * Progress bar.
 * Based on https://stackoverflow.com/a/43381186/574082.
 */
public class ProgressBar {
    private final long total;
    private final int numStripes;
    private final long startTime;
    private int previousPercentage;

    public ProgressBar(long total, int numStripes) {
        if (total < 0) {
            throw new IllegalArgumentException("Total of progress bar must be positive");
        }
        if (numStripes <= 0) {
            throw new IllegalArgumentException("Number of stripes in progress bar must be positive");
        }
        this.total = total;
        this.numStripes = numStripes;
        this.previousPercentage = -1;
        this.startTime = System.currentTimeMillis();
    }

    public synchronized void update(long remain) {
        if (remain > total || remain < 0) {
            throw new IllegalArgumentException(
                    "Update value " + remain + " out of range [0, " + total + "].");
        }
        int remainPercent;
        if (total > 0) {
            remainPercent = Math.min((int) ((100 * remain) / total), 100);
        } else {
            remainPercent = 100;
        }
        if (remainPercent == previousPercentage) {
            return;
        }
        previousPercentage = remainPercent;
        int stripesFilled = numStripes * remainPercent / 100;
        char notFilled = '-';
        char filled = '*';
        // 2 init + numStripes + 2 end + 4 percentage
        StringBuilder builder = new StringBuilder(numStripes + 25);
        builder.append("\r[");
        for (int i = 0; i < stripesFilled; i++) {
            builder.append(filled);
        }
        for (int i = stripesFilled; i < numStripes; i++) {
            builder.append(notFilled);
        }
        builder.append("] ").append(remainPercent)
                .append("% - ETA ");

        if (remain > 0) {
            long remainingSeconds = (System.currentTimeMillis() - startTime) * (total - remain) / (remain * 1000L);
            long remainingMinutes = (remainingSeconds / 60) % 60;
            long remainingSecondsShort = remainingSeconds % 60;
            builder.append(remainingSeconds / 3600)
                    .append(':');
            if (remainingMinutes < 10) {
                builder.append('0');
            }
            builder.append(remainingMinutes)
                    .append(':');
            if (remainingSecondsShort < 10) {
                builder.append('0');
            }
            builder.append(remainingSecondsShort);
        } else {
            builder.append('-');
        }

        if (remain < total) {
            System.out.print(builder.toString());
        } else {
            System.out.println(builder.toString());
        }
    }

    public boolean isDone() {
        return previousPercentage == 100;
    }
}
