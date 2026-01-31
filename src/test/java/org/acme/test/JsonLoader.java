package org.acme.test;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 테스트 리소스를 로드하는 유틸리티 클래스.
 */
public class JsonLoader {

    /**
     * 클래스패스에서 JSON 리소스를 로드하여 지정된 타입으로 변환합니다.
     *
     * @param resourcePath JSON 리소스 경로 (예: "/json/sample.json")
     * @param clazz 변환할 클래스 타입
     * @param objectMapper 사용할 ObjectMapper 인스턴스
     * @param <T> 변환할 타입
     * @return 변환된 객체
     * @throws IOException 리소스를 찾을 수 없거나 읽기 실패 시
     */
    public static <T> T load(String resourcePath, Class<T> clazz, ObjectMapper objectMapper) throws IOException {
        try (InputStream inputStream = JsonLoader.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return objectMapper.readValue(inputStream, clazz);
        }
    }

    /**
     * 클래스패스에서 JSON 리소스를 로드하여 지정된 타입으로 변환합니다.
     * 기본 ObjectMapper를 사용합니다 (Quarkus 테스트에서는 Jackson JSR310 모듈이 필요할 수 있음).
     *
     * @param resourcePath JSON 리소스 경로 (예: "/json/sample.json")
     * @param clazz 변환할 클래스 타입
     * @param <T> 변환할 타입
     * @return 변환된 객체
     * @throws IOException 리소스를 찾을 수 없거나 읽기 실패 시
     */
    public static <T> T load(String resourcePath, Class<T> clazz) throws IOException {
        try (InputStream inputStream = JsonLoader.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            // 기본 ObjectMapper 사용
            ObjectMapper mapper = new ObjectMapper();
            // Jackson JSR310 모듈 등록 (Java 8 날짜/시간 타입 지원)
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            return mapper.readValue(inputStream, clazz);
        }
    }

    /**
     * 클래스패스에서 JSON 리소스를 문자열로 로드합니다.
     *
     * @param resourcePath JSON 리소스 경로 (예: "/json/sample.json")
     * @return JSON 문자열
     * @throws IOException 리소스를 찾을 수 없거나 읽기 실패 시
     */
    public static String loadAsString(String resourcePath) throws IOException {
        try (InputStream inputStream = JsonLoader.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes());
        }
    }
}
