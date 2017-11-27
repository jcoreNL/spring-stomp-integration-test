package com.jcore.stomp;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class BaseStompIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private BlockingQueue<String> receivedMessages;

    private StompSession session;

    private static final String SUBSCRIPTION_TOPIC = "/topic/myTopic";
    private static final Logger LOGGER = LoggerFactory.getLogger(BaseStompIntegrationTest.class);

    @Autowired
    SimpMessagingTemplate messagingTemplate;

    @Before
    public void setup() throws InterruptedException, ExecutionException, TimeoutException {
        final String URL = "ws://localhost:" + port + "/myendpoint";

        final List<Transport> transportList = Collections.singletonList(new WebSocketTransport(new StandardWebSocketClient()));
        final WebSocketStompClient stompClient = new WebSocketStompClient(new SockJsClient(transportList));
        stompClient.setMessageConverter(new StringMessageConverter());

        receivedMessages = new LinkedBlockingDeque<>();
        session = stompClient.connect(URL, new MySessionHandler()).get(5, SECONDS);
        await().until(this::isSubscribed);
    }

    @Test
    public void stompTest() throws Exception {
        final String message = "myMessage";
        messagingTemplate.convertAndSend(SUBSCRIPTION_TOPIC, message);
        final String response = receivedMessages.poll(5, SECONDS);
        Assert.assertEquals(message, response);
    }

    @After
    public void reset() throws InterruptedException {
        session.disconnect();
        await().until(() -> !session.isConnected());
    }

    private boolean isSubscribed() {
        final String message = UUID.randomUUID().toString();

        messagingTemplate.convertAndSend(SUBSCRIPTION_TOPIC, message);

        String response = null;
        try {
            response = receivedMessages.poll(20, MILLISECONDS);

            // drain the message queue before returning true
            while(response!= null && !message.equals(response)) {
                LOGGER.debug("Draining message queue");
                response = receivedMessages.poll(20, MILLISECONDS);
            }

        } catch (InterruptedException e) {
            LOGGER.debug("Polling received messages interrupted", e);
        }

        return response != null;
    }

    private class MySessionHandler extends StompSessionHandlerAdapter {
        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            session.subscribe(SUBSCRIPTION_TOPIC, this);
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
            LOGGER.warn("Stomp Error:", exception);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            super.handleTransportError(session, exception);
            LOGGER.warn("Stomp Transport Error:", exception);
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders stompHeaders, Object o) {
            LOGGER.info("Handle Frame with payload: {}", o);
            try {
                receivedMessages.offer((String) o, 500, MILLISECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}