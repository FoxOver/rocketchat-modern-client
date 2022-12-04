package com.github.daniel_sc.rocketchat.modern_client;

import com.github.daniel_sc.rocketchat.modern_client.exception.RocketChatClientCloseReasonException;
import com.github.daniel_sc.rocketchat.modern_client.exception.RocketChatClientException;
import com.github.daniel_sc.rocketchat.modern_client.exception.SendFailedException;
import com.github.daniel_sc.rocketchat.modern_client.request.*;
import com.github.daniel_sc.rocketchat.modern_client.response.*;
import com.github.daniel_sc.rocketchat.modern_client.response.livechat.InitialData;
import com.github.daniel_sc.rocketchat.modern_client.response.livechat.LiveChatMessage;
import com.github.daniel_sc.rocketchat.modern_client.response.livechat.RegisterGuest;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;

import javax.websocket.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RocketChatClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RocketChatClient.class.getName());

    protected static final Gson GSON = new GsonBuilder()
            // parse dates from long:
            .registerTypeAdapter(Date.class, (JsonDeserializer<Date>) (json, typeOfT, context) -> new Date(json.getAsJsonPrimitive().getAsLong()))
            .registerTypeAdapter(Date.class, (JsonSerializer<Date>) (date, type, jsonSerializationContext) -> new JsonPrimitive(date.getTime()))
            .registerTypeAdapter(FullChatMessage.class, new ChatMessageDeserializer())
            .create();

    protected final Map<String, CompletableFutureWithMapper<?>> futureResults = new ConcurrentHashMap<>();
    protected final ConcurrentHashMap<String, ObservableSubjectWithMapper<?>> subscriptionResults = new ConcurrentHashMap<>();

    protected final CompletableFuture<Session> session = new CompletableFuture<>();
    protected final String url;
    protected final CompletableFuture<String> connectResult = new CompletableFuture<>();
    protected final AtomicBoolean connectionTerminated = new AtomicBoolean(false);
    protected final CompletableFuture<LoginResult> login;
    protected final Executor executor;

    public RocketChatClient(String url, String user, String password) {
        this(url, user, password, ForkJoinPool.commonPool());
    }

    public RocketChatClient(String url, String user, String password, Executor executor) {
        this.url = url;
        this.executor = executor;
        login = login(new LoginParam(user, password));
    }

    public RocketChatClient(String url, LoginParam param) throws RocketChatClientException {
        this(url, param, ForkJoinPool.commonPool());
    }

    public RocketChatClient(String url, LoginParam param, Executor executor) throws RocketChatClientException {
        this.url = url;
        this.executor = executor;
        login = login(param);
        try {
            login.join();
        } catch (CompletionException e) {
            try {
                throw e.getCause();
            } catch (RocketChatClientException ex) {
                throw ex;
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        }
    }

    public RocketChatClient(String url, LoginTokenParam param) {
        this(url, param, ForkJoinPool.commonPool());
    }

    public RocketChatClient(String url, LoginTokenParam param, Executor executor) {
        this.url = url;
        this.executor = executor;
        login = loginWithToken(param);
    }

    public RocketChatClient(String url, LoginOAuthParam param) {
        this(url, param, ForkJoinPool.commonPool());
    }

    public RocketChatClient(String url, LoginOAuthParam param, Executor executor) {
        this.url = url;
        this.executor = executor;
        login = login(param);
    }

    protected CompletableFuture<String> connect() {
        //noinspection ResultOfMethodCallIgnored
        rawMessages.subscribe(msg -> {
            GenericAnswer msgObject = GSON.fromJson(msg, GenericAnswer.class);
            if ("connected".equals(msgObject.msg)) {
                connectResult.complete(msgObject.session);
            } else if ("ping".equals(msgObject.msg)) {
                session.join().getAsyncRemote().sendText("{\"msg\":\"ping\"}",
                        result -> LOG.fine("sent pong: " + result.isOK()));
            } else if (msgObject.id != null && futureResults.containsKey(msgObject.id)) {
                boolean complete = futureResults.remove(msgObject.id).completeAndMap(msgObject);
                if (!complete) {
                    LOG.warning("future result was already completed: " + msgObject);
                }
            } else if (msgObject.fields != null
                    && msgObject.fields.get("eventName") != null
                    && subscriptionResults.containsKey(msgObject.fields.get("eventName"))) {
                subscriptionResults.get(msgObject.fields.get("eventName")).next(msgObject);
            } else {
                LOG.warning("Unhandled message: " + msg);
            }
        }, e -> LOG.log(Level.SEVERE, "message receiving failed", e));
        if (!connectResult.isDone()) {
            try {
                LOG.info("connecting to " + url);
                WSClient clientEndpoint = new WSClient();
                session.complete(ContainerProvider.getWebSocketContainer().connectToServer(clientEndpoint, URI.create(url)));
                LOG.fine("created session: " + session.join());
                LOG.fine("sending connect");
                session.join().getAsyncRemote().sendText("{\"msg\": \"connect\",\"version\": \"1\",\"support\": [\"1\"]}",
                        sendResult -> LOG.fine("connect ack: " + sendResult.isOK()));
            } catch (Exception e) {
                session.completeExceptionally(e);
                throw new IllegalStateException(e);
            }
        }
        return connectResult;
    }

    protected Subject<String> rawMessages = PublishSubject.create();

    public Observable<String> getRawMessages() {
        return rawMessages;
    }

    /**
     * @return user id if successfully logged in
     */
    public String getLoggedInUserId() {
        return login.thenApply(loginResult -> loginResult.id).getNow(null);
    }

    public CompletableFuture<LoginResult> login(LoginParam param) {
        return loginBase(param);
    }

    private CompletableFuture<LoginResult> loginWithToken(LoginTokenParam param) {
        return loginBase(param);
    }

    private CompletableFuture<LoginResult> login(LoginOAuthParam param) {
        return loginBase(param);
    }

    private CompletableFuture<LoginResult> loginBase(Object param) {
        return connect().thenComposeAsync(session -> sendDirect(new MethodRequest("login", param),
                failOnError(r -> GSON.fromJson(r.result, LoginResult.class))), executor);
    }

    public CompletableFuture<List<Subscription>> getSubscriptions() {
        return send(new MethodRequest("subscriptions/get"),
                failOnError(genericAnswer -> {
                    JsonElement jsonElement = GSON.toJsonTree(genericAnswer.result);
                    return GSON.fromJson(jsonElement, new TypeToken<List<Subscription>>() {
                    }.getType());
                }));
    }

    public CompletableFuture<List<Room>> getRooms() {
        return send(new MethodRequest("rooms/get"),
                failOnError(genericAnswer -> {
                    JsonElement jsonElement = GSON.toJsonTree(genericAnswer.result);
                    return GSON.fromJson(jsonElement, new TypeToken<List<Room>>() {
                    }.getType());
                }));
    }

    public CompletableFuture<DirectMessage> getUserId(String username) {
        return send(new MethodRequest("createDirectMessage", username),
                genericAnswer -> {
                    JsonElement jsonElement = GSON.toJsonTree(genericAnswer.result);
                    return GSON.fromJson(jsonElement, new TypeToken<DirectMessage>() {
                    }.getType());
                });
    }

    protected <T> CompletableFuture<T> send(IRequest request, Function<GenericAnswer, T> answerMapper) {
        return login.thenComposeAsync(token -> sendDirect(request, answerMapper), executor);

    }

    protected <T> CompletableFuture<T> sendDirect(IRequest request, Function<GenericAnswer, T> answerMapper) {
        if (connectionTerminated.get()) {
            throw new IllegalStateException("connection already closed!");
        }
        CompletableFutureWithMapper<T> result = new CompletableFutureWithMapper<>(answerMapper);
        futureResults.put(request.getId(), result);
        String requestString = GSON.toJson(request);
        LOG.fine("REQUEST: " + requestString);
        session.join().getAsyncRemote().sendText(requestString, sendResult -> handleSendResult(sendResult, result));
        return result;
    }

    public CompletableFuture<ChatMessage> sendMessage(String msg, String rid) {
        return sendMessageExtendedParams(msg, rid, null, null, null, null, null);
    }

    public CompletableFuture<ChatMessage> sendMessageExtendedParams(String msg, String rid, String alias, String avatar, String emoji, Boolean groupable, List<Attachment> attachments) {
        MethodRequest request = new MethodRequest("sendMessage", SendMessageParam.forSendMessage(msg, rid, alias, avatar, emoji, groupable, attachments));
        return send(request, failOnError(r -> {
            if (r.result == null) {
                throw new IllegalStateException("Message result is empty!");
            }
            return GSON.fromJson(r.result, ChatMessage.class);
        }));
    }

    public CompletableFuture<ChatMessage> updateMessage(String msg, String _id) {
        return updateMessageWithAttachments(msg, _id, null, null);
    }

    public CompletableFuture<ChatMessage> updateMessageWithAttachments(String msg, String _id, Boolean groupable, List<Attachment> attachments) {
        MethodRequest request = new MethodRequest("updateMessage", SendMessageParam.forUpdate(_id, msg, null, null, null, null, groupable, attachments));
        return send(request, failOnError(r -> GSON.fromJson(GSON.toJsonTree(r.result), ChatMessage.class)));
    }

    public CompletableFuture<List<Permission>> getPermissions() {
        return send(new MethodRequest("permissions/get"),
                failOnError(r -> GSON.fromJson(GSON.toJsonTree(r.result), new TypeToken<List<Permission>>() {
                }.getType())));
    }

    public CompletableFuture<InitialData> livechatGetInitialData(String visitorToken) {
        MethodRequest request;
        if (visitorToken != null) {
            request = new MethodRequest("livechat:getInitialData", visitorToken);
        } else {
            request = new MethodRequest("livechat:getInitialData");
        }
        return send(request, failOnError(r -> GSON.fromJson(GSON.toJsonTree(r.result), InitialData.class)));
    }

    public CompletableFuture<Void> livechatSendOfflineMessage(String msg, String name, String email) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        params.put("message", msg);
        params.put("email", email);

        return send(new MethodRequest("livechat:sendOfflineMessage", params), failOnError(r -> null));
    }

    public CompletableFuture<LiveChatMessage> livechatSendMessage(String msg, String roomId, String visitorToken) {
        Map<String, Object> params = new HashMap<>();
        params.put("_id", UUID.randomUUID().toString());
        params.put("rid", roomId);
        params.put("msg", msg);
        params.put("token", visitorToken);

        return send(new MethodRequest("sendMessageLivechat", params),
                failOnError(r -> GSON.fromJson(GSON.toJsonTree(r.result), LiveChatMessage.class)));
    }

    public CompletableFuture<RegisterGuest> livechatRegisterGuest(String visitorToken, String name, String email, String department) {
        Map<String, Object> params = new HashMap<>();
        params.put("token", visitorToken);
        if (name != null) {
            params.put("name", name);
        }
        if (email != null) {
            params.put("email", email);
        }
        if (department != null) {
            params.put("department", department);
        }
        return send(new MethodRequest("livechat:registerGuest", params),
                failOnError(r -> GSON.fromJson(GSON.toJsonTree(r.result), RegisterGuest.class)));
    }

    public Observable<ChatMessage> streamLivechatRoom(String roomName, String visitorToken) {
        // contrary to docs, we need "visitorToken" (instead of "token") and "stream-room-messages" (instead of "stream-livechat-room") - see https://stackoverflow.com/a/62025100/2544163
        Function<GenericAnswer, ChatMessage> mapper = r -> GSON.fromJson(GSON.toJsonTree(((List<?>) r.fields.get("args")).get(0)), ChatMessage.class);
        Map<String, Object> secondParam = new HashMap<>();
        secondParam.put("useCollection", false);
        secondParam.put("args", Collections.singletonList(Collections.singletonMap("visitorToken", visitorToken)));
        return getStream(roomName, new SubscriptionRequest("stream-room-messages", roomName, secondParam), mapper);
    }

    protected <T> Function<GenericAnswer, T> failOnError(Function<GenericAnswer, T> mapper) {
        return result -> {
            if (result.error != null) {
                throw new RuntimeException("Send message failed: " + GSON.toJson(result.error));
            } else {
                return mapper.apply(result);
            }
        };
    }

    protected void failMethodLogin(ErrorMessage message) {
        login.completeExceptionally(new RocketChatClientException(message.message));
        close();
    }

    /**
     * Subscribes to chat room messages. Subscription is automatically managed,
     * i.e. the chat room subscription starts when the first subscriber is attached
     * to the subject and the subscription is cancelled once the last subscriber of
     * the subject is disposed.
     *
     * @param rid room id
     * @return lazily initialized stream of chat room messages
     */
    public Observable<ChatMessage> streamRoomMessages(String rid) {
        SubscriptionRequest request = new SubscriptionRequest("stream-room-messages", rid, false);
        Function<GenericAnswer, ChatMessage> mapper = r -> GSON.fromJson(GSON.toJsonTree(((List<?>) r.fields.get("args")).get(0)), ChatMessage.class);

        return getStream(rid, request, mapper);
    }

    protected <T> Observable<T> getStream(String rid, SubscriptionRequest request, Function<GenericAnswer, T> mapper) {
        subscriptionResults.computeIfAbsent(rid, roomId -> {
            LOG.fine("creating new subscription observable");
            PublishSubject<T> subject = PublishSubject.create();
            Observable<T> observable = subject.doFinally(() -> {
                LOG.fine("cancelling subscription");
                subscriptionResults.remove(rid);
                send(new UnsubscribeRequest(request.getId()), failOnError(Function.identity()))
                        .handleAsync((r, error) -> {
                            LOG.fine("handling unsubscribe: result=" + r + ", error=" + error);
                            if (error != null) {
                                // this happens when client is closed immediately after disposing observer..
                                LOG.log(Level.FINE, "Failed to unsubscribe: ", error);
                            }
                            return r;
                        }, executor);
            }).share();

            send(request, failOnError(Function.identity()))
                    .handleAsync((r, error) -> {
                        LOG.fine("handling subscribe: result=" + r + ", error=" + error);
                        if (error != null) {
                            subject.onError(error);
                        }
                        return r;
                    }, executor);

            return new ObservableSubjectWithMapper<>(subject, observable, mapper);
        });
        //noinspection unchecked
        return (Observable<T>) subscriptionResults.get(rid).getObservable();
    }

    public Observable<FullChatMessage> streamMessages() {
        String myMessage = "__my_messages__";
        subscriptionResults.computeIfAbsent(myMessage, roomId -> {
            LOG.fine("creating new subscription observable");
            SubscriptionRequest request = new SubscriptionRequest("stream-room-messages", myMessage, false);
            PublishSubject<FullChatMessage> subject = PublishSubject.create();
            Observable<FullChatMessage> observable = subject.doFinally(() -> {
                LOG.fine("cancelling subscription");
                subscriptionResults.remove(myMessage);
                send(new UnsubscribeRequest(request.getId()), failOnError(Function.identity()))
                        .handleAsync((r, error) -> {
                            LOG.fine("handling unsubscribe: result=" + r + ", error=" + error);
                            if (error != null) {
                                // this happens when client is closed immediately after disposing observer..
                                LOG.log(Level.FINE, "Failed to unsubscribe: ", error);
                            }
                            return r;
                        }, executor);
            }).share();

            send(request, failOnError(Function.identity()))
                    .handleAsync((r, error) -> {
                        LOG.fine("handling subscribe: result=" + r + ", error=" + error);
                        if (error != null) {
                            subject.onError(error);
                        }
                        return r;
                    }, executor);

            return new ObservableSubjectWithMapper<>(subject, observable,
                    r -> GSON.fromJson(GSON.toJsonTree(r.fields), FullChatMessage.class));
        });
        //noinspection unchecked
        return (Observable<FullChatMessage>) subscriptionResults.get(myMessage).getObservable();
    }

    private static void handleSendResult(SendResult sendResult, CompletableFuture<?> result) {
        if (!sendResult.isOK()) {
            result.completeExceptionally(new SendFailedException(sendResult));
        }
    }

    @Override
    public void close() {
        LOG.fine("closing client..");
        try {
            subscriptionResults.clear();
            session.join().close();
        } catch (IOException | CompletionException | CancellationException e) {
            LOG.log(Level.WARNING, "Could not close session: ", e);
        }
        rawMessages.onComplete();
    }

    @ClientEndpoint
    public class WSClient {

        private final List<String> messageParts = new ArrayList<>();

        public WSClient() {
            LOG.fine("created WSClient");
        }

        @SuppressWarnings({"unused"})
        @OnMessage
        public void onMessage(String message, boolean last) {
            LOG.fine("Received msg (last part: " + last + "): " + message);
            if (last) {
                String completeMessage;
                synchronized (messageParts) { // not really clear if synchronization is necessary here, better save than sorry..
                    completeMessage = String.join("", messageParts) + message;
                    messageParts.clear();
                }

                GenericAnswer msgObject = GSON.fromJson(completeMessage, GenericAnswer.class);
                if (msgObject.server_id != null) {
                    LOG.fine("sending connect");
                    session.join().getAsyncRemote().sendText("{\"msg\": \"connect\",\"version\": \"1\",\"support\": [\"1\"]}",
                            sendResult -> LOG.fine("connect ack: " + sendResult.isOK()));
                } else if ("connected".equals(msgObject.msg)) {
                    connectResult.complete(msgObject.session);
                } else if (msgObject.error != null) {
                    failMethodLogin((ErrorMessage) msgObject.error);
                } else if ("ping".equals(msgObject.msg)) {
                    session.join().getAsyncRemote().sendText("{\"msg\":\"ping\"}",
                            result -> LOG.fine("sent pong: " + result.isOK()));
                } else if (msgObject.id != null && futureResults.containsKey(msgObject.id)) {
                    boolean complete = futureResults.remove(msgObject.id).completeAndMap(msgObject);
                    if (!complete) {
                        LOG.warning("future result was already completed: " + msgObject);
                    }
                } else if (msgObject.fields != null
                        && msgObject.fields.get("eventName") != null
                        && subscriptionResults.containsKey(msgObject.fields.get("eventName"))) {
                    subscriptionResults.get(msgObject.fields.get("eventName")).next(msgObject);
                } else {
                    LOG.warning("Unhandled message: " + completeMessage);
                }

            } else {
                synchronized (messageParts) {
                    messageParts.add(message);
                }
            }
        }

        @OnClose
        public void onClose(CloseReason closeReason) {
            LOG.warning("connection closed: " + closeReason);
            connectionTerminated.set(true);

            futureResults.forEach((id, future) -> {
                // this can happen for future results of cancelled subscriptions or pong messages..
                LOG.fine("terminating open result id=" + id + ", future=" + future +
                        " (you might want to 'join()' some result before closing the client to prevent this)");
                future.completeExceptionally(new RocketChatClientCloseReasonException(closeReason));
            });
            futureResults.clear();
            subscriptionResults.forEach((id, observerAndMapper) -> {
                LOG.warning("terminating open subscription id=" + id + ", observable=" + observerAndMapper.getObservable() +
                        " (you might want to dispose all observers before closing the client to prevent this)");
                observerAndMapper.getSubject().onComplete();
            });
            subscriptionResults.clear();
        }

    }

}
