/*
 * Copyright 2013-2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.whispersystems.textsecuregcm.auth.AuthenticatedAccount;
import org.whispersystems.textsecuregcm.entities.MessageProtos;
import org.whispersystems.textsecuregcm.entities.MessageProtos.Envelope;
import org.whispersystems.textsecuregcm.push.ReceiptSender;
import org.whispersystems.textsecuregcm.redis.RedisClusterExtension;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.Device;
import org.whispersystems.textsecuregcm.storage.DynamoDbExtension;
import org.whispersystems.textsecuregcm.storage.MessagesCache;
import org.whispersystems.textsecuregcm.storage.MessagesDynamoDb;
import org.whispersystems.textsecuregcm.storage.MessagesManager;
import org.whispersystems.textsecuregcm.storage.ReportMessageManager;
import org.whispersystems.textsecuregcm.tests.util.MessagesDynamoDbExtension;
import org.whispersystems.textsecuregcm.util.Pair;
import org.whispersystems.websocket.WebSocketClient;
import org.whispersystems.websocket.messages.WebSocketResponseMessage;
import reactor.core.scheduler.Schedulers;

class WebSocketConnectionIntegrationTest {

  @RegisterExtension
  static DynamoDbExtension dynamoDbExtension = MessagesDynamoDbExtension.build();

  @RegisterExtension
  static final RedisClusterExtension REDIS_CLUSTER_EXTENSION = RedisClusterExtension.builder().build();

  private ExecutorService sharedExecutorService;
  private MessagesDynamoDb messagesDynamoDb;
  private MessagesCache messagesCache;
  private ReportMessageManager reportMessageManager;
  private Account account;
  private Device device;
  private WebSocketClient webSocketClient;
  private ScheduledExecutorService retrySchedulingExecutor;

  private long serialTimestamp = System.currentTimeMillis();

  @BeforeEach
  void setUp() throws Exception {

    sharedExecutorService = Executors.newSingleThreadExecutor();
    messagesCache = new MessagesCache(REDIS_CLUSTER_EXTENSION.getRedisCluster(),
        REDIS_CLUSTER_EXTENSION.getRedisCluster(), Clock.systemUTC(), sharedExecutorService, sharedExecutorService);
    messagesDynamoDb = new MessagesDynamoDb(dynamoDbExtension.getDynamoDbClient(),
        dynamoDbExtension.getDynamoDbAsyncClient(), MessagesDynamoDbExtension.TABLE_NAME, Duration.ofDays(7),
        sharedExecutorService);
    reportMessageManager = mock(ReportMessageManager.class);
    account = mock(Account.class);
    device = mock(Device.class);
    webSocketClient = mock(WebSocketClient.class);
    retrySchedulingExecutor = Executors.newSingleThreadScheduledExecutor();

    when(account.getNumber()).thenReturn("+18005551234");
    when(account.getUuid()).thenReturn(UUID.randomUUID());
    when(device.getId()).thenReturn(1L);
  }

  @AfterEach
  void tearDown() throws Exception {
    sharedExecutorService.shutdown();
    sharedExecutorService.awaitTermination(2, TimeUnit.SECONDS);

    retrySchedulingExecutor.shutdown();
    retrySchedulingExecutor.awaitTermination(2, TimeUnit.SECONDS);
  }

  @ParameterizedTest
  @CsvSource({
      "207, 173, true",
      "207, 173, false",
      "323, 0, true",
      "323, 0, false",
      "0, 221, true",
      "0, 221, false",
  })
  void testProcessStoredMessages(final int persistedMessageCount, final int cachedMessageCount,
      final boolean useReactive) {
    final WebSocketConnection webSocketConnection = new WebSocketConnection(
        mock(ReceiptSender.class),
        new MessagesManager(messagesDynamoDb, messagesCache, reportMessageManager, sharedExecutorService),
        new AuthenticatedAccount(() -> new Pair<>(account, device)),
        device,
        webSocketClient,
        retrySchedulingExecutor,
        useReactive);

    final List<MessageProtos.Envelope> expectedMessages = new ArrayList<>(persistedMessageCount + cachedMessageCount);

    assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {

      {
        final List<MessageProtos.Envelope> persistedMessages = new ArrayList<>(persistedMessageCount);

        for (int i = 0; i < persistedMessageCount; i++) {
          final MessageProtos.Envelope envelope = generateRandomMessage(UUID.randomUUID());

          persistedMessages.add(envelope);
          expectedMessages.add(envelope);
        }

        messagesDynamoDb.store(persistedMessages, account.getUuid(), device.getId());
      }

      for (int i = 0; i < cachedMessageCount; i++) {
        final UUID messageGuid = UUID.randomUUID();
        final MessageProtos.Envelope envelope = generateRandomMessage(messageGuid);

        messagesCache.insert(messageGuid, account.getUuid(), device.getId(), envelope);
        expectedMessages.add(envelope);
      }

      final WebSocketResponseMessage successResponse = mock(WebSocketResponseMessage.class);
      final AtomicBoolean queueCleared = new AtomicBoolean(false);

      when(successResponse.getStatus()).thenReturn(200);
      when(webSocketClient.sendRequest(eq("PUT"), eq("/api/v1/message"), anyList(), any()))
          .thenReturn(CompletableFuture.completedFuture(successResponse));

      when(webSocketClient.sendRequest(eq("PUT"), eq("/api/v1/queue/empty"), anyList(), any())).thenAnswer(
          (Answer<CompletableFuture<WebSocketResponseMessage>>) invocation -> {
            synchronized (queueCleared) {
              queueCleared.set(true);
              queueCleared.notifyAll();
            }

            return CompletableFuture.completedFuture(successResponse);
          });

      webSocketConnection.processStoredMessages();

      synchronized (queueCleared) {
        while (!queueCleared.get()) {
          queueCleared.wait();
        }
      }

      @SuppressWarnings("unchecked") final ArgumentCaptor<Optional<byte[]>> messageBodyCaptor = ArgumentCaptor.forClass(
          Optional.class);

      verify(webSocketClient, times(persistedMessageCount + cachedMessageCount)).sendRequest(eq("PUT"),
          eq("/api/v1/message"), anyList(), messageBodyCaptor.capture());
      verify(webSocketClient).sendRequest(eq("PUT"), eq("/api/v1/queue/empty"), anyList(), eq(Optional.empty()));

      final List<MessageProtos.Envelope> sentMessages = new ArrayList<>();

      for (final Optional<byte[]> maybeMessageBody : messageBodyCaptor.getAllValues()) {
        maybeMessageBody.ifPresent(messageBytes -> {
          try {
            sentMessages.add(MessageProtos.Envelope.parseFrom(messageBytes));
          } catch (final InvalidProtocolBufferException e) {
            fail("Could not parse sent message");
          }
        });
      }

      assertEquals(expectedMessages, sentMessages);
    });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testProcessStoredMessagesClientClosed(final boolean useReactive) {
    final WebSocketConnection webSocketConnection = new WebSocketConnection(
        mock(ReceiptSender.class),
        new MessagesManager(messagesDynamoDb, messagesCache, reportMessageManager, sharedExecutorService),
        new AuthenticatedAccount(() -> new Pair<>(account, device)),
        device,
        webSocketClient,
        retrySchedulingExecutor,
        useReactive);

    final int persistedMessageCount = 207;
    final int cachedMessageCount = 173;

    final List<MessageProtos.Envelope> expectedMessages = new ArrayList<>(persistedMessageCount + cachedMessageCount);

    assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {

      {
        final List<MessageProtos.Envelope> persistedMessages = new ArrayList<>(persistedMessageCount);

        for (int i = 0; i < persistedMessageCount; i++) {
          final MessageProtos.Envelope envelope = generateRandomMessage(UUID.randomUUID());
          persistedMessages.add(envelope);
          expectedMessages.add(envelope);
        }

        messagesDynamoDb.store(persistedMessages, account.getUuid(), device.getId());
      }

      for (int i = 0; i < cachedMessageCount; i++) {
        final UUID messageGuid = UUID.randomUUID();
        final MessageProtos.Envelope envelope = generateRandomMessage(messageGuid);
        messagesCache.insert(messageGuid, account.getUuid(), device.getId(), envelope);

        expectedMessages.add(envelope);
      }

      when(webSocketClient.sendRequest(eq("PUT"), eq("/api/v1/message"), anyList(), any())).thenReturn(
          CompletableFuture.failedFuture(new IOException("Connection closed")));

      webSocketConnection.processStoredMessages();

      //noinspection unchecked
      ArgumentCaptor<Optional<byte[]>> messageBodyCaptor = ArgumentCaptor.forClass(Optional.class);

      verify(webSocketClient, atMost(persistedMessageCount + cachedMessageCount)).sendRequest(eq("PUT"),
          eq("/api/v1/message"), anyList(), messageBodyCaptor.capture());
      verify(webSocketClient, never()).sendRequest(eq("PUT"), eq("/api/v1/queue/empty"), anyList(),
          eq(Optional.empty()));

      final List<MessageProtos.Envelope> sentMessages = messageBodyCaptor.getAllValues().stream()
          .map(Optional::get)
          .map(messageBytes -> {
            try {
              return Envelope.parseFrom(messageBytes);
            } catch (InvalidProtocolBufferException e) {
              throw new RuntimeException(e);
            }
          }).toList();

      assertTrue(expectedMessages.containsAll(sentMessages));
    });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testProcessStoredMessagesSendFutureTimeout(final boolean useReactive) {
    final WebSocketConnection webSocketConnection = new WebSocketConnection(
        mock(ReceiptSender.class),
        new MessagesManager(messagesDynamoDb, messagesCache, reportMessageManager, sharedExecutorService),
        new AuthenticatedAccount(() -> new Pair<>(account, device)),
        device,
        webSocketClient,
        100, // use a very short timeout, so that this test completes quickly
        retrySchedulingExecutor,
        useReactive,
        Schedulers.boundedElastic());

    final int persistedMessageCount = 207;
    final int cachedMessageCount = 173;

    final List<MessageProtos.Envelope> expectedMessages = new ArrayList<>(persistedMessageCount + cachedMessageCount);

    assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {

      {
        final List<MessageProtos.Envelope> persistedMessages = new ArrayList<>(persistedMessageCount);

        for (int i = 0; i < persistedMessageCount; i++) {
          final MessageProtos.Envelope envelope = generateRandomMessage(UUID.randomUUID());
          persistedMessages.add(envelope);
          expectedMessages.add(envelope);
        }

        messagesDynamoDb.store(persistedMessages, account.getUuid(), device.getId());
      }

      for (int i = 0; i < cachedMessageCount; i++) {
        final UUID messageGuid = UUID.randomUUID();
        final MessageProtos.Envelope envelope = generateRandomMessage(messageGuid);
        messagesCache.insert(messageGuid, account.getUuid(), device.getId(), envelope);

        expectedMessages.add(envelope);
      }

      final WebSocketResponseMessage successResponse = mock(WebSocketResponseMessage.class);
      when(successResponse.getStatus()).thenReturn(200);

      final CompletableFuture<WebSocketResponseMessage> neverCompleting = new CompletableFuture<>();

      // for the first message, return a future that never completes
      when(webSocketClient.sendRequest(eq("PUT"), eq("/api/v1/message"), anyList(), any()))
          .thenReturn(neverCompleting)
          .thenReturn(CompletableFuture.completedFuture(successResponse));

      when(webSocketClient.isOpen()).thenReturn(true);

      final AtomicBoolean queueCleared = new AtomicBoolean(false);

      when(webSocketClient.sendRequest(eq("PUT"), eq("/api/v1/queue/empty"), anyList(), any())).thenAnswer(
          (Answer<CompletableFuture<WebSocketResponseMessage>>) invocation -> {
            synchronized (queueCleared) {
              queueCleared.set(true);
              queueCleared.notifyAll();
            }

            return CompletableFuture.completedFuture(successResponse);
          });

      webSocketConnection.processStoredMessages();

      synchronized (queueCleared) {
        while (!queueCleared.get()) {
          queueCleared.wait();
        }
      }

      //noinspection unchecked
      ArgumentCaptor<Optional<byte[]>> messageBodyCaptor = ArgumentCaptor.forClass(Optional.class);

      // We expect all of the messages from both pools to be sent, plus one for the future that times out
      verify(webSocketClient, atMost(persistedMessageCount + cachedMessageCount + 1)).sendRequest(eq("PUT"),
          eq("/api/v1/message"), anyList(), messageBodyCaptor.capture());

      verify(webSocketClient).sendRequest(eq("PUT"), eq("/api/v1/queue/empty"), anyList(), eq(Optional.empty()));

      final List<MessageProtos.Envelope> sentMessages = messageBodyCaptor.getAllValues().stream()
          .map(Optional::get)
          .map(messageBytes -> {
            try {
              return Envelope.parseFrom(messageBytes);
            } catch (InvalidProtocolBufferException e) {
              throw new RuntimeException(e);
            }
          }).toList();

      assertTrue(expectedMessages.containsAll(sentMessages));
    });
  }

  private MessageProtos.Envelope generateRandomMessage(final UUID messageGuid) {
    final long timestamp = serialTimestamp++;

    return MessageProtos.Envelope.newBuilder()
        .setTimestamp(timestamp)
        .setServerTimestamp(timestamp)
        .setContent(ByteString.copyFromUtf8(RandomStringUtils.randomAlphanumeric(256)))
        .setType(MessageProtos.Envelope.Type.CIPHERTEXT)
        .setServerGuid(messageGuid.toString())
        .setDestinationUuid(UUID.randomUUID().toString())
        .build();
  }

}
