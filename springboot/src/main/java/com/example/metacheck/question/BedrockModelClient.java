package com.example.metacheck.question;

import java.time.Duration;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.InternalServerException;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

/**
 * 실제 Bedrock 호출 (Converse API). 호출마다 비용이 들어 설정 metacheck.ai.client=bedrock 일 때만 켜진다.
 * Node 러너 run-bedrock.mjs 의 makeInvoke 와 같은 호출 · 재시도다. 자격 증명은 AWS 기본 경로(EC2 인스턴스 프로파일 등)로 찾고,
 * 코드나 설정 파일에 키를 두지 않는다.
 */
@Component
@ConditionalOnProperty(name = "metacheck.ai.client", havingValue = "bedrock")
public class BedrockModelClient implements ModelClient, AutoCloseable {

    static final int MAX_CALLS = 4;
    static final long BACKOFF_MILLIS = 2000;

    private final BedrockRuntimeClient client;
    private final String basicModelId;
    private final String advancedModelId;
    private final LongConsumer sleeper;

    @Autowired
    public BedrockModelClient(@Value("${metacheck.ai.bedrock.region:}") String region,
            @Value("${metacheck.ai.bedrock.basic-model-id:}") String basicModelId,
            @Value("${metacheck.ai.bedrock.advanced-model-id:}") String advancedModelId,
            @Value("${metacheck.ai.bedrock.timeout-seconds:60}") int timeoutSeconds) {
        this(build(region, basicModelId, advancedModelId, Duration.ofSeconds(timeoutSeconds)), basicModelId, advancedModelId, BedrockModelClient::sleep);
    }

    BedrockModelClient(BedrockRuntimeClient client, String basicModelId, String advancedModelId, LongConsumer sleeper) {
        this.client = client;
        this.basicModelId = basicModelId;
        this.advancedModelId = advancedModelId;
        this.sleeper = sleeper;
    }

    // 값이 빠졌으면 첫 호출 때가 아니라 시작할 때 멈춘다
    private static BedrockRuntimeClient build(String region, String basicModelId, String advancedModelId, Duration timeout) {
        if (isBlank(region) || isBlank(basicModelId) || isBlank(advancedModelId)) {
            throw new IllegalStateException("Bedrock 설정이 없습니다. metacheck.ai.bedrock.region · basic-model-id · advanced-model-id 를 "
                    + "주거나(환경변수 BEDROCK_REGION · BEDROCK_BASIC_MODEL_ID · BEDROCK_ADVANCED_MODEL_ID), metacheck.ai.client 를 mock 으로 두세요.");
        }
        return BedrockRuntimeClient.builder()
                .region(Region.of(region.trim()))
                // Converse 는 응답이 다 만들어져야 첫 바이트가 온다. 기본 소켓 제한(30초)이면 긴 생성이 끊긴다.
                .httpClientBuilder(Apache5HttpClient.builder().socketTimeout(timeout))
                // 재시도는 invoke 가 맡는다. SDK 재시도까지 겹치면 호출 수가 곱절이 된다.
                .overrideConfiguration(c -> c.retryStrategy(AwsRetryStrategy.doNotRetry()).apiCallTimeout(timeout))
                .build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("Bedrock 재시도 대기 중 중단됨", e);
        }
    }

    @Override
    public Reply invoke(Request request) {
        ConverseRequest converse = toConverse(request);
        for (int call = 1; ; call++) {
            try {
                return toReply(client.converse(converse));
            } catch (ThrottlingException | ServiceUnavailableException | ModelNotReadyException | InternalServerException e) {
                if (call >= MAX_CALLS) {
                    throw new ModelCallException("Bedrock 호출 실패 (재시도 " + (MAX_CALLS - 1) + "회 후): " + e.getClass().getSimpleName(), e);
                }
                sleeper.accept(BACKOFF_MILLIS * call);
            } catch (SdkException e) {
                throw new ModelCallException("Bedrock 호출 실패: " + e.getClass().getSimpleName(), e);
            }
        }
    }

    private ConverseRequest toConverse(Request request) {
        List<Message> messages = request.messages().stream()
                .map(t -> Message.builder().role(ConversationRole.fromValue(t.role())).content(ContentBlock.fromText(t.text())).build())
                .toList();
        return ConverseRequest.builder()
                .modelId(request.tier() == Tier.ADVANCED ? advancedModelId : basicModelId)
                .system(SystemContentBlock.fromText(request.system()))
                .messages(messages)
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(request.maxTokens())
                        .temperature((float) request.temperature())
                        .build())
                .build();
    }

    private static Reply toReply(ConverseResponse response) {
        String text = response.output() == null || response.output().message() == null ? "" : response.output().message().content().stream()
                .map(ContentBlock::text)
                .filter(t -> t != null)
                .collect(Collectors.joining());
        return new Reply(text, response.stopReasonAsString());
    }

    @Override
    public void close() {
        client.close();
    }
}
