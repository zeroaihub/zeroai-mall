package com.zeroai.util.util.json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * JSON 工具。Spring Boot 4 起默认 Jackson 3，包名由 com.fasterxml.jackson 迁至 tools.jackson，
 * 且 JacksonException 为 RuntimeException（不再声明受检异常），故统一按 JacksonException 捕获。
 */
public class JsonUtil {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final ObjectWriter objectWriter = objectMapper.writerWithDefaultPrettyPrinter();

    public static void marshal(File file, Object value) throws Exception {
        try {
            objectWriter.writeValue(file, value);
        } catch (JacksonException e) {
            throw new Exception(e);
        }
    }

    public static void marshal(OutputStream os, Object value) throws Exception {
        try {
            objectWriter.writeValue(os, value);
        } catch (JacksonException e) {
            throw new Exception(e);
        }
    }

    public static String marshal(Object value) throws Exception {
        try {
            return objectWriter.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new Exception(e);
        }
    }

    public static byte[] marshalBytes(Object value) throws Exception {
        try {
            return objectWriter.writeValueAsBytes(value);
        } catch (JacksonException e) {
            throw new Exception(e);
        }
    }

    public static <T> T unmarshal(File file, Class<T> valueType) throws Exception {
        try {
            return objectMapper.readValue(file, valueType);
        } catch (JacksonException e) {
            throw new Exception(e);
        }
    }

    public static <T> T unmarshal(InputStream is, Class<T> valueType) throws Exception {
        try {
            return objectMapper.readValue(is, valueType);
        } catch (JacksonException e) {
            throw new Exception(e);
        }
    }

    public static <T> T unmarshal(String str, Class<T> valueType) throws Exception {
        try {
            return objectMapper.readValue(str, valueType);
        } catch (JacksonException e) {
            throw new Exception(e);
        }
    }

    public static <T> T unmarshal(byte[] bytes, Class<T> valueType) throws Exception {
        try {
            if (bytes == null) {
                bytes = new byte[0];
            }
            return objectMapper.readValue(bytes, 0, bytes.length, valueType);
        } catch (JacksonException e) {
            throw new Exception(e);
        }
    }

}
