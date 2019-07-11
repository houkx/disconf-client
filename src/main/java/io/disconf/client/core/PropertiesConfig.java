package io.disconf.client.core;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Properties 配置文件修改操作
 *
 * @author houkangxi
 */
public class PropertiesConfig {
    final File file;
    List<String> lines = new ArrayList<>(256);
    Map<String, Integer> key2line = new HashMap<>();
    boolean escUnicode = true;

    public PropertiesConfig(File file) throws IOException {
        this.file = file;
        AtomicInteger i = new AtomicInteger();
        try (LineNumberReader reader = new LineNumberReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (!line.trim().startsWith("#")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq);
                        key2line.put(key, i.get());
                    }
                }
                i.getAndIncrement();
            }
        }
    }

    /**
     * 保存修改到文件
     *
     * @param properties
     * @param comments
     * @throws IOException
     */
    public void saveToFile(Properties properties, String comments) throws IOException {
        try (PrintWriter writer = new PrintWriter(file, "UTF-8")) {
            key2line.forEach((k, i) -> {
                Object v = properties.remove(k);
                if (v != null) {
                    String val = kv(k, v.toString());
                    lines.set(i, val);
                }
            });
            for (String val : lines) {
                writer.println(val);
            }
            writer.flush();
            if (comments != null) {
                writeComments(writer, comments);
            }
            properties.forEach((k, v) -> {
                String val = kv(k.toString(), v.toString());
                writer.println(val);
            });
        }
    }

    private String kv(String key, String val) {
        key = saveConvert(key, true, escUnicode);
        /* No need to escape embedded and trailing spaces for value, hence
         * pass false to flag.
         */
        val = saveConvert(val, false, escUnicode);
        return (key + "=" + val);
    }

    /*
     * Converts unicodes to encoded &#92;uxxxx and escapes
     * special characters with a preceding slash
     */
    private String saveConvert(String theString,
                               boolean escapeSpace,
                               boolean escapeUnicode) {
        int len = theString.length();
        int bufLen = len * 2;
        if (bufLen < 0) {
            bufLen = Integer.MAX_VALUE;
        }
        StringBuffer outBuffer = new StringBuffer(bufLen);

        for (int x = 0; x < len; x++) {
            char aChar = theString.charAt(x);
            // Handle common case first, selecting largest block that
            // avoids the specials below
            if ((aChar > 61) && (aChar < 127)) {
                if (aChar == '\\') {
                    outBuffer.append('\\');
                    outBuffer.append('\\');
                    continue;
                }
                outBuffer.append(aChar);
                continue;
            }
            switch (aChar) {
                case ' ':
                    if (x == 0 || escapeSpace)
                        outBuffer.append('\\');
                    outBuffer.append(' ');
                    break;
                case '\t':
                    outBuffer.append('\\');
                    outBuffer.append('t');
                    break;
                case '\n':
                    outBuffer.append('\\');
                    outBuffer.append('n');
                    break;
                case '\r':
                    outBuffer.append('\\');
                    outBuffer.append('r');
                    break;
                case '\f':
                    outBuffer.append('\\');
                    outBuffer.append('f');
                    break;
                case '=': // Fall through
                case ':': // Fall through
                case '#': // Fall through
                case '!':
                    outBuffer.append('\\');
                    outBuffer.append(aChar);
                    break;
                default:
                    if (((aChar < 0x0020) || (aChar > 0x007e)) & escapeUnicode) {
                        outBuffer.append('\\');
                        outBuffer.append('u');
                        outBuffer.append(toHex((aChar >> 12) & 0xF));
                        outBuffer.append(toHex((aChar >> 8) & 0xF));
                        outBuffer.append(toHex((aChar >> 4) & 0xF));
                        outBuffer.append(toHex(aChar & 0xF));
                    } else {
                        outBuffer.append(aChar);
                    }
            }
        }
        return outBuffer.toString();
    }

    /**
     * Convert a nibble to a hex character
     *
     * @param nibble the nibble to convert.
     */
    private static char toHex(int nibble) {
        return hexDigit[(nibble & 0xF)];
    }

    /**
     * A table of hex digits
     */
    private static final char[] hexDigit = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    private static void writeComments(PrintWriter bw, String comments)
            throws IOException {
        bw.write("#");
        int len = comments.length();
        int current = 0;
        int last = 0;
        char[] uu = new char[6];
        uu[0] = '\\';
        uu[1] = 'u';
        while (current < len) {
            char c = comments.charAt(current);
            if (c > '\u00ff' || c == '\n' || c == '\r') {
                if (last != current)
                    bw.print(comments.substring(last, current));
                if (c > '\u00ff') {
                    uu[2] = toHex((c >> 12) & 0xf);
                    uu[3] = toHex((c >> 8) & 0xf);
                    uu[4] = toHex((c >> 4) & 0xf);
                    uu[5] = toHex(c & 0xf);
                    bw.print(new String(uu));
                } else {
                    bw.println();
                    if (c == '\r' &&
                            current != len - 1 &&
                            comments.charAt(current + 1) == '\n') {
                        current++;
                    }
                    if (current == len - 1 ||
                            (comments.charAt(current + 1) != '#' &&
                                    comments.charAt(current + 1) != '!'))
                        bw.print("#");
                }
                last = current + 1;
            }
            current++;
        }
        if (last != current)
            bw.print(comments.substring(last, current));
        bw.println();
    }
}