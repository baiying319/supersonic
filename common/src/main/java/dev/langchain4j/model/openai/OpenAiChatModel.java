package dev.langchain4j.model.openai;

import dev.ai4j.openai4j.OpenAiClient;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.TokenCountEstimator;
import dev.langchain4j.model.chat.listener.ChatLanguageModelRequest;
import dev.langchain4j.model.chat.listener.ChatLanguageModelResponse;
import dev.langchain4j.model.listener.ModelListener;
import dev.langchain4j.model.openai.spi.OpenAiChatModelBuilderFactory;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.internal.RetryUtils.withRetry;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.model.openai.InternalOpenAiHelper.DEFAULT_USER_AGENT;
import static dev.langchain4j.model.openai.InternalOpenAiHelper.OPENAI_DEMO_API_KEY;
import static dev.langchain4j.model.openai.InternalOpenAiHelper.OPENAI_DEMO_URL;
import static dev.langchain4j.model.openai.InternalOpenAiHelper.OPENAI_URL;
import static dev.langchain4j.model.openai.InternalOpenAiHelper.aiMessageFrom;
import static dev.langchain4j.model.openai.InternalOpenAiHelper.createModelListenerRequest;
import static dev.langchain4j.model.openai.InternalOpenAiHelper.createModelListenerResponse;
import static dev.langchain4j.model.openai.InternalOpenAiHelper.finishReasonFrom;
import static dev.langchain4j.model.openai.InternalOpenAiHelper.toOpenAiMessages;
import static dev.langchain4j.model.openai.InternalOpenAiHelper.toTools;
import static dev.langchain4j.model.openai.InternalOpenAiHelper.tokenUsageFrom;
import static dev.langchain4j.model.openai.OpenAiModelName.GPT_3_5_TURBO;
import static dev.langchain4j.spi.ServiceHelper.loadFactories;
import static java.time.Duration.ofSeconds;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/**
 * Represents an OpenAI language model with a chat completion interface, such as gpt-3.5-turbo and
 * gpt-4. You can find description of parameters <a
 * href="https://platform.openai.com/docs/api-reference/chat/create">here</a>.
 */
@Slf4j
public class OpenAiChatModel implements ChatLanguageModel, TokenCountEstimator {

    public static final String ZHIPU = "bigmodel";
    private final OpenAiClient client;
    private final String baseUrl;
    private final String modelName;
    private final Double temperature;
    private final Double topP;
    private final List<String> stop;
    private final Integer maxTokens;
    private final Double presencePenalty;
    private final Double frequencyPenalty;
    private final Map<String, Integer> logitBias;
    private final String responseFormat;
    private final Integer seed;
    private final String user;
    private final Integer maxRetries;
    private final Tokenizer tokenizer;

    private final List<ModelListener<ChatLanguageModelRequest, ChatLanguageModelResponse>>
            listeners;

    @Builder
    public OpenAiChatModel(
            String baseUrl,
            String apiKey,
            String organizationId,
            String modelName,
            Double temperature,
            Double topP,
            List<String> stop,
            Integer maxTokens,
            Double presencePenalty,
            Double frequencyPenalty,
            Map<String, Integer> logitBias,
            String responseFormat,
            Integer seed,
            String user,
            Duration timeout,
            Integer maxRetries,
            Proxy proxy,
            Boolean logRequests,
            Boolean logResponses,
            Tokenizer tokenizer,
            Map<String, String> customHeaders,
            List<ModelListener<ChatLanguageModelRequest, ChatLanguageModelResponse>> listeners) {

        baseUrl = getOrDefault(baseUrl, OPENAI_URL);
        if (OPENAI_DEMO_API_KEY.equals(apiKey)) {
            baseUrl = OPENAI_DEMO_URL;
        }
        this.baseUrl = baseUrl;

        timeout = getOrDefault(timeout, ofSeconds(60));

        this.client =
                OpenAiClient.builder()
                        .openAiApiKey(apiKey)
                        .baseUrl(baseUrl)
                        .organizationId(organizationId)
                        .callTimeout(timeout)
                        .connectTimeout(timeout)
                        .readTimeout(timeout)
                        .writeTimeout(timeout)
                        .proxy(proxy)
                        .logRequests(logRequests)
                        .logResponses(logResponses)
                        .userAgent(DEFAULT_USER_AGENT)
                        .customHeaders(customHeaders)
                        .build();
        this.modelName = getOrDefault(modelName, GPT_3_5_TURBO);
        this.temperature = getOrDefault(temperature, 0.7);
        this.topP = topP;
        this.stop = stop;
        this.maxTokens = maxTokens;
        this.presencePenalty = presencePenalty;
        this.frequencyPenalty = frequencyPenalty;
        this.logitBias = logitBias;
        this.responseFormat = responseFormat;
        this.seed = seed;
        this.user = user;
        this.maxRetries = getOrDefault(maxRetries, 3);
        this.tokenizer = getOrDefault(tokenizer, OpenAiTokenizer::new);
        this.listeners = listeners == null ? emptyList() : new ArrayList<>(listeners);
    }

    public String modelName() {
        return modelName;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, null, null);
    }

    @Override
    public Response<AiMessage> generate(
            List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        return generate(messages, toolSpecifications, null);
    }

    @Override
    public Response<AiMessage> generate(
            List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return generate(messages, singletonList(toolSpecification), toolSpecification);
    }

    private Response<AiMessage> generate(
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecifications,
            ToolSpecification toolThatMustBeExecuted) {
        ChatCompletionRequest.Builder requestBuilder =
                ChatCompletionRequest.builder()
                        .model(modelName)
                        .messages(toOpenAiMessages(messages))
                        .topP(topP)
                        .stop(stop)
                        .maxTokens(maxTokens)
                        .presencePenalty(presencePenalty)
                        .frequencyPenalty(frequencyPenalty)
                        .logitBias(logitBias)
                        .responseFormat(responseFormat)
                        .seed(seed)
                        .user(user);
        if (!(baseUrl.contains(ZHIPU))) {
            requestBuilder.temperature(temperature);
        }

        if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
            requestBuilder.tools(toTools(toolSpecifications));
        }
        if (toolThatMustBeExecuted != null) {
            requestBuilder.toolChoice(toolThatMustBeExecuted.name());
        }

        ChatCompletionRequest request = requestBuilder.build();

        ChatLanguageModelRequest modelListenerRequest =
                createModelListenerRequest(request, messages, toolSpecifications);
        listeners.forEach(
                listener -> {
                    try {
                        listener.onRequest(modelListenerRequest);
                    } catch (Exception e) {
                        log.warn("Exception while calling model listener", e);
                    }
                });

        try {
            ChatCompletionResponse chatCompletionResponse =
                    withRetry(() -> client.chatCompletion(request).execute(), maxRetries);

            Response<AiMessage> response =
                    Response.from(
                            aiMessageFrom(chatCompletionResponse),
                            tokenUsageFrom(chatCompletionResponse.usage()),
                            finishReasonFrom(
                                    chatCompletionResponse.choices().get(0).finishReason()));

            ChatLanguageModelResponse modelListenerResponse =
                    createModelListenerResponse(
                            chatCompletionResponse.id(), chatCompletionResponse.model(), response);
            listeners.forEach(
                    listener -> {
                        try {
                            listener.onResponse(modelListenerResponse, modelListenerRequest);
                        } catch (Exception e) {
                            log.warn("Exception while calling model listener", e);
                        }
                    });

            return response;
        } catch (RuntimeException e) {

            Throwable error;
            if (e.getCause() instanceof OpenAiHttpException) {
                error = e.getCause();
            } else {
                error = e;
            }

            listeners.forEach(
                    listener -> {
                        try {
                            listener.onError(error, null, modelListenerRequest);
                        } catch (Exception e2) {
                            log.warn("Exception while calling model listener", e2);
                        }
                    });
            throw e;
        }
    }

    @Override
    public int estimateTokenCount(List<ChatMessage> messages) {
        return tokenizer.estimateTokenCountInMessages(messages);
    }

    public static OpenAiChatModel withApiKey(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    public static OpenAiChatModelBuilder builder() {
        for (OpenAiChatModelBuilderFactory factory :
                loadFactories(OpenAiChatModelBuilderFactory.class)) {
            return factory.get();
        }
        return new OpenAiChatModelBuilder();
    }

    public static class OpenAiChatModelBuilder {

        public OpenAiChatModelBuilder() {
            // This is public so it can be extended
            // By default with Lombok it becomes package private
        }

        public OpenAiChatModelBuilder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }
    }
}
