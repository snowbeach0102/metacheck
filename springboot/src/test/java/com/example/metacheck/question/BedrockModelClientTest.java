package com.example.metacheck.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;
import tools.jackson.databind.JsonNode;

/** 실제 AWS 에 접속하지 않는다. 미리 정해 둔 결과를 돌려주는 가짜 Bedrock 클라이언트로 요청 모양 · 재시도 · 예외 처리를 본다. */
class BedrockModelClientTest {

    /** 정해 둔 응답이나 예외를 차례로 내고, 받은 요청을 기록한다. */
    private static final class Scripted implements BedrockRuntimeClient {
        final Deque<Object> results = new ArrayDeque<>();
        final List<ConverseRequest> requests = new ArrayList<>();
        boolean closed;

        Scripted(Object... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public ConverseResponse converse(ConverseRequest request) {
            requests.add(request);
            Object next = results.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (ConverseResponse) next;
        }

        @Override
        public String serviceName() {
            return "bedrock";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static ConverseResponse response(StopReason stop, String... texts) {
        List<ContentBlock> blocks = List.of(texts).stream().map(ContentBlock::fromText).toList();
        return ConverseResponse.builder()
                .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT).content(blocks).build()))
                .stopReason(stop)
                .build();
    }

    private final List<Long> slept = new ArrayList<>();

    private BedrockModelClient client(Scripted bedrock) {
        return new BedrockModelClient(bedrock, "basic-id", "advanced-id", slept::add);
    }

    private static ModelClient.Request request(ModelClient.Tier tier) {
        return new ModelClient.Request("시스템", List.of(new ModelClient.Turn("user", "질문"), new ModelClient.Turn("assistant", "답"),
                new ModelClient.Turn("user", "다시")), 2000, 0.7, tier);
    }

    @Test
    void 요청을_Converse_모양으로_바꾸고_글자를_이어_돌려준다() {
        Scripted bedrock = new Scripted(response(StopReason.END_TURN, "앞", "뒤"));
        ModelClient.Reply reply = client(bedrock).invoke(request(ModelClient.Tier.BASIC));

        assertThat(reply.text()).isEqualTo("앞뒤");
        assertThat(reply.stopReason()).isEqualTo("end_turn");
        ConverseRequest sent = bedrock.requests.get(0);
        assertThat(sent.modelId()).isEqualTo("basic-id");
        assertThat(sent.system()).hasSize(1);
        assertThat(sent.system().get(0).text()).isEqualTo("시스템");
        assertThat(sent.messages()).extracting(Message::roleAsString).containsExactly("user", "assistant", "user");
        assertThat(sent.messages().get(2).content().get(0).text()).isEqualTo("다시");
        assertThat(sent.inferenceConfig().maxTokens()).isEqualTo(2000);
        assertThat(sent.inferenceConfig().temperature()).isEqualTo(0.7f);
        assertThat(slept).isEmpty();
    }

    @Test
    void 상위_등급은_상위_모델_ID_로_보낸다() {
        Scripted bedrock = new Scripted(response(StopReason.END_TURN, "ok"));
        client(bedrock).invoke(request(ModelClient.Tier.ADVANCED));
        assertThat(bedrock.requests.get(0).modelId()).isEqualTo("advanced-id");
    }

    @Test
    void 일시_오류는_2초씩_늘려_기다리며_다시_부른다() {
        Scripted bedrock = new Scripted(ThrottlingException.builder().message("느리게").build(),
                ModelNotReadyException.builder().message("준비 중").build(), response(StopReason.END_TURN, "ok"));
        ModelClient.Reply reply = client(bedrock).invoke(request(ModelClient.Tier.BASIC));

        assertThat(reply.text()).isEqualTo("ok");
        assertThat(bedrock.requests).hasSize(3);
        assertThat(slept).containsExactly(2000L, 4000L);
    }

    @Test
    void 일시_오류가_계속되면_4번까지_부르고_호출_실패로_알린다() {
        Scripted bedrock = new Scripted(ThrottlingException.builder().build(), ThrottlingException.builder().build(),
                ThrottlingException.builder().build(), ThrottlingException.builder().build(), response(StopReason.END_TURN, "안 씀"));

        assertThatThrownBy(() -> client(bedrock).invoke(request(ModelClient.Tier.BASIC)))
                .isInstanceOf(ModelCallException.class).hasCauseInstanceOf(ThrottlingException.class);
        assertThat(bedrock.requests).hasSize(4);
        assertThat(slept).containsExactly(2000L, 4000L, 6000L);
    }

    @Test
    void 권한_오류나_잘못된_요청은_다시_부르지_않는다() {
        Scripted denied = new Scripted(AccessDeniedException.builder().message("권한 없음").build());
        assertThatThrownBy(() -> client(denied).invoke(request(ModelClient.Tier.BASIC)))
                .isInstanceOf(ModelCallException.class).hasCauseInstanceOf(AccessDeniedException.class);
        assertThat(denied.requests).hasSize(1);

        Scripted invalid = new Scripted(ValidationException.builder().message("잘못된 모델 ID").build());
        assertThatThrownBy(() -> client(invalid).invoke(request(ModelClient.Tier.BASIC)))
                .isInstanceOf(ModelCallException.class).hasCauseInstanceOf(ValidationException.class);
        assertThat(invalid.requests).hasSize(1);
        assertThat(slept).isEmpty();
    }

    @Test
    void 네트워크_오류도_호출_실패로_알린다() {
        Scripted bedrock = new Scripted(SdkClientException.create("접속 실패"));
        assertThatThrownBy(() -> client(bedrock).invoke(request(ModelClient.Tier.BASIC)))
                .isInstanceOf(ModelCallException.class).hasCauseInstanceOf(SdkClientException.class);
    }

    @Test
    void 글자_없는_응답은_빈_글자로_돌려준다() {
        Scripted bedrock = new Scripted(ConverseResponse.builder().stopReason(StopReason.CONTENT_FILTERED).build());
        ModelClient.Reply reply = client(bedrock).invoke(request(ModelClient.Tier.BASIC));
        assertThat(reply.text()).isEmpty();
        assertThat(reply.stopReason()).isEqualTo("content_filtered");
    }

    @Test
    void 실제_AWS_SDK로_보내면_Converse_요청과_응답이_오간다() throws Exception {
        // 로컬 가짜 서버로 보내므로 AWS 에 접속하지 않고 비용도 없다. 앱과 같은 HTTP 클라이언트를 쓴다
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = """
                    {"output":{"message":{"role":"assistant","content":[{"text":"문항"}]}},"stopReason":"max_tokens",
                     "usage":{"inputTokens":1,"outputTokens":1,"totalTokens":2},"metrics":{"latencyMs":1}}""".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try (BedrockRuntimeClient real = BedrockRuntimeClient.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(Apache5HttpClient.builder())
                .build()) {
            ModelClient.Reply reply = new BedrockModelClient(real, "basic-id", "advanced-id", slept::add).invoke(request(ModelClient.Tier.ADVANCED));

            assertThat(reply.text()).isEqualTo("문항");
            assertThat(reply.stopReason()).isEqualTo("max_tokens");
        } finally {
            server.stop(0);
        }

        assertThat(path.get()).isEqualTo("/model/advanced-id/converse");
        JsonNode sent = Json.MAPPER.readTree(body.get());
        assertThat(sent.get("system").get(0).get("text").asString()).isEqualTo("시스템");
        assertThat(sent.get("messages")).hasSize(3);
        assertThat(sent.get("messages").get(1).get("role").asString()).isEqualTo("assistant");
        assertThat(sent.get("inferenceConfig").get("maxTokens").asInt()).isEqualTo(2000);
    }

    @Test
    void 닫으면_AWS_클라이언트도_닫는다() {
        Scripted bedrock = new Scripted();
        client(bedrock).close();
        assertThat(bedrock.closed).isTrue();
    }
}
