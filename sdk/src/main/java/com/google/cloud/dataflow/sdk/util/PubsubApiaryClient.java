/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.util;

import static com.google.common.base.Preconditions.checkState;

import com.google.api.services.pubsub.Pubsub;
import com.google.api.services.pubsub.Pubsub.Builder;
import com.google.api.services.pubsub.model.AcknowledgeRequest;
import com.google.api.services.pubsub.model.ListSubscriptionsResponse;
import com.google.api.services.pubsub.model.ListTopicsResponse;
import com.google.api.services.pubsub.model.ModifyAckDeadlineRequest;
import com.google.api.services.pubsub.model.PublishRequest;
import com.google.api.services.pubsub.model.PublishResponse;
import com.google.api.services.pubsub.model.PubsubMessage;
import com.google.api.services.pubsub.model.PullRequest;
import com.google.api.services.pubsub.model.PullResponse;
import com.google.api.services.pubsub.model.ReceivedMessage;
import com.google.api.services.pubsub.model.Subscription;
import com.google.api.services.pubsub.model.Topic;
import com.google.cloud.dataflow.sdk.options.DataflowPipelineOptions;
import com.google.cloud.hadoop.util.ChainingHttpRequestInitializer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

/**
 * A Pubsub client using Apiary.
 */
public class PubsubApiaryClient extends PubsubClient {

  private static class PubsubApiaryClientFactory implements PubsubClientFactory {
    @Override
    public PubsubClient newClient(
        @Nullable String timestampLabel, @Nullable String idLabel, DataflowPipelineOptions options)
        throws IOException {
      Pubsub pubsub = new Builder(
          Transport.getTransport(),
          Transport.getJsonFactory(),
          new ChainingHttpRequestInitializer(
              options.getGcpCredential(),
              // Do not log 404. It clutters the output and is possibly even required by the caller.
              new RetryHttpRequestInitializer(ImmutableList.of(404))))
          .setRootUrl(options.getPubsubRootUrl())
          .setApplicationName(options.getAppName())
          .setGoogleClientRequestInitializer(options.getGoogleApiTrace())
          .build();
      return new PubsubApiaryClient(timestampLabel, idLabel, pubsub);
    }

    @Override
    public String getKind() {
      return "Apiary";
    }
  }

  /**
   * Factory for creating Pubsub clients using Apiary transport.
   */
  public static final PubsubClientFactory FACTORY = new PubsubApiaryClientFactory();

  /**
   * Label to use for custom timestamps, or {@literal null} if should use Pubsub publish time
   * instead.
   */
  @Nullable
  private final String timestampLabel;

  /**
   * Label to use for custom ids, or {@literal null} if should use Pubsub provided ids.
   */
  @Nullable
  private final String idLabel;

  /**
   * Underlying Apiary client.
   */
  private Pubsub pubsub;

  @VisibleForTesting
  PubsubApiaryClient(
      @Nullable String timestampLabel,
      @Nullable String idLabel,
      Pubsub pubsub) {
    this.timestampLabel = timestampLabel;
    this.idLabel = idLabel;
    this.pubsub = pubsub;
  }

  @Override
  public void close() {
    // Nothing to close.
  }

  @Override
  public int publish(TopicPath topic, List<OutgoingMessage> outgoingMessages)
      throws IOException {
    List<PubsubMessage> pubsubMessages = new ArrayList<>(outgoingMessages.size());
    for (OutgoingMessage outgoingMessage : outgoingMessages) {
      PubsubMessage pubsubMessage = new PubsubMessage().encodeData(outgoingMessage.elementBytes);

      Map<String, String> attributes = pubsubMessage.getAttributes();
      if ((timestampLabel != null || idLabel != null) && attributes == null) {
        attributes = new TreeMap<>();
        pubsubMessage.setAttributes(attributes);
      }

      if (timestampLabel != null) {
        attributes.put(timestampLabel, String.valueOf(outgoingMessage.timestampMsSinceEpoch));
      }

      if (idLabel != null && !Strings.isNullOrEmpty(outgoingMessage.recordId)) {
        attributes.put(idLabel, outgoingMessage.recordId);
      }

      pubsubMessages.add(pubsubMessage);
    }
    PublishRequest request = new PublishRequest().setMessages(pubsubMessages);
    PublishResponse response = pubsub.projects()
                                     .topics()
                                     .publish(topic.getPath(), request)
                                     .execute();
    return response.getMessageIds().size();
  }

  @Override
  public List<IncomingMessage> pull(
      long requestTimeMsSinceEpoch,
      SubscriptionPath subscription,
      int batchSize,
      boolean returnImmediately) throws IOException {
    PullRequest request = new PullRequest()
        .setReturnImmediately(returnImmediately)
        .setMaxMessages(batchSize);
    PullResponse response = pubsub.projects()
                                  .subscriptions()
                                  .pull(subscription.getPath(), request)
                                  .execute();
    if (response.getReceivedMessages() == null || response.getReceivedMessages().size() == 0) {
      return ImmutableList.of();
    }
    List<IncomingMessage> incomingMessages = new ArrayList<>(response.getReceivedMessages().size());
    for (ReceivedMessage message : response.getReceivedMessages()) {
      PubsubMessage pubsubMessage = message.getMessage();
      @Nullable Map<String, String> attributes = pubsubMessage.getAttributes();

      // Payload.
      byte[] elementBytes = pubsubMessage.decodeData();

      // Timestamp.
      long timestampMsSinceEpoch =
          extractTimestamp(timestampLabel, message.getMessage().getPublishTime(), attributes);

      // Ack id.
      String ackId = message.getAckId();
      checkState(!Strings.isNullOrEmpty(ackId));

      // Record id, if any.
      @Nullable String recordId = null;
      if (idLabel != null && attributes != null) {
        recordId = attributes.get(idLabel);
      }
      if (Strings.isNullOrEmpty(recordId)) {
        // Fall back to the Pubsub provided message id.
        recordId = pubsubMessage.getMessageId();
      }

      incomingMessages.add(new IncomingMessage(elementBytes, timestampMsSinceEpoch,
                                               requestTimeMsSinceEpoch, ackId, recordId));
    }

    return incomingMessages;
  }

  @Override
  public void acknowledge(SubscriptionPath subscription, List<String> ackIds) throws IOException {
    AcknowledgeRequest request = new AcknowledgeRequest().setAckIds(ackIds);
    pubsub.projects()
          .subscriptions()
          .acknowledge(subscription.getPath(), request)
          .execute(); // ignore Empty result.
  }

  @Override
  public void modifyAckDeadline(
      SubscriptionPath subscription, List<String> ackIds, int deadlineSeconds)
      throws IOException {
    ModifyAckDeadlineRequest request =
        new ModifyAckDeadlineRequest().setAckIds(ackIds)
                                      .setAckDeadlineSeconds(deadlineSeconds);
    pubsub.projects()
          .subscriptions()
          .modifyAckDeadline(subscription.getPath(), request)
          .execute(); // ignore Empty result.
  }

  @Override
  public void createTopic(TopicPath topic) throws IOException {
    pubsub.projects()
          .topics()
          .create(topic.getPath(), new Topic())
          .execute(); // ignore Topic result.
  }

  @Override
  public void deleteTopic(TopicPath topic) throws IOException {
    pubsub.projects()
          .topics()
          .delete(topic.getPath())
          .execute(); // ignore Empty result.
  }

  @Override
  public List<TopicPath> listTopics(ProjectPath project) throws IOException {
    ListTopicsResponse response = pubsub.projects()
                                        .topics()
                                        .list(project.getPath())
                                        .execute();
    if (response.getTopics() == null || response.getTopics().isEmpty()) {
      return ImmutableList.of();
    }
    List<TopicPath> topics = new ArrayList<>(response.getTopics().size());
    for (Topic topic : response.getTopics()) {
      topics.add(topicPathFromPath(topic.getName()));
    }
    return topics;
  }

  @Override
  public void createSubscription(
      TopicPath topic, SubscriptionPath subscription,
      int ackDeadlineSeconds) throws IOException {
    Subscription request = new Subscription()
        .setTopic(topic.getPath())
        .setAckDeadlineSeconds(ackDeadlineSeconds);
    pubsub.projects()
          .subscriptions()
          .create(subscription.getPath(), request)
          .execute(); // ignore Subscription result.
  }

  @Override
  public void deleteSubscription(SubscriptionPath subscription) throws IOException {
    pubsub.projects()
          .subscriptions()
          .delete(subscription.getPath())
          .execute(); // ignore Empty result.
  }

  @Override
  public List<SubscriptionPath> listSubscriptions(ProjectPath project, TopicPath topic)
      throws IOException {
    ListSubscriptionsResponse response = pubsub.projects()
                                               .subscriptions()
                                               .list(project.getPath())
                                               .execute();
    if (response.getSubscriptions() == null || response.getSubscriptions().isEmpty()) {
      return ImmutableList.of();
    }
    List<SubscriptionPath> subscriptions = new ArrayList<>(response.getSubscriptions().size());
    for (Subscription subscription : response.getSubscriptions()) {
      if (subscription.getTopic().equals(topic.getPath())) {
        subscriptions.add(subscriptionPathFromPath(subscription.getName()));
      }
    }
    return subscriptions;
  }

  @Override
  public int ackDeadlineSeconds(SubscriptionPath subscription) throws IOException {
    Subscription response = pubsub.projects().subscriptions().get(subscription.getPath()).execute();
    return response.getAckDeadlineSeconds();
  }

  @Override
  public boolean isEOF() {
    return false;
  }
}
