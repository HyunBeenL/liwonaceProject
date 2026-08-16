package com.leehv1234.reaoneproject.dataset;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 대회 데이터셋(companyx-dataset)의 위치와 적재 동작 설정.
 *
 * <p>데이터셋은 "대회 참가 목적으로만 사용 가능" 라이선스라 저장소에 커밋하지 않는다.
 * 따라서 경로를 외부에서 지정할 수 있어야 한다.
 *
 * @param path           데이터셋 루트 디렉터리 (sql/, documents/, graph/ 를 포함)
 * @param loadOnStartup  기동 시 그래프·문서 적재 여부
 */
@ConfigurationProperties(prefix = "app.dataset")
public record DatasetProperties(String path, boolean loadOnStartup) {
}
