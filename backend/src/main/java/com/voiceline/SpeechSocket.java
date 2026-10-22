package com.voiceline;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;

@Component
@EnableScheduling
public class SpeechSocket extends AbstractWebSocketHandler {
  private final TranscriptService service;
  private final ObjectMapper json;
  private final String url, token;
  private final int maxStreams;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
  private final Map<String, Link> links = new ConcurrentHashMap<>();
  private final Set<String> owners = ConcurrentHashMap.newKeySet();

  public SpeechSocket(
      TranscriptService service,
      ObjectMapper json,
      @Value("${app.speech-url}") String url,
      @Value("${app.speech-token}") String token,
      @Value("${app.max-streams}") int maxStreams) {
    this.service = service;
    this.json = json;
    this.url = url;
    this.token = token;
    this.maxStreams = maxStreams;
  }

  private static class Link {
    final WebSocketSession browser;
    final String owner, id;
    final long opened = System.currentTimeMillis();
    volatile java.net.http.WebSocket worker;
    long bytes;
    volatile boolean stopping;
    final AtomicBoolean closed = new AtomicBoolean();

    Link(WebSocketSession b, String o, String id) {
      browser = new ConcurrentWebSocketSessionDecorator(b, 10000, 262144);
      owner = o;
      this.id = id;
    }
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    session.setBinaryMessageSizeLimit(8192);
    session.setTextMessageSizeLimit(1024);
    var owner = (String) session.getAttributes().get("owner");
    var path = session.getUri().getPath().split("/");
    var id = path[path.length - 2];
    var link = new Link(session, owner, id);
    synchronized (links) {
      if (links.size() >= maxStreams || !owners.add(owner)) {
        session.sendMessage(
            new TextMessage(
                "{\"type\":\"error\",\"message\":\"Recording capacity is busy. Stop your other"
                    + " recording or try again shortly.\"}"));
        session.close(CloseStatus.POLICY_VIOLATION);
        return;
      }
      links.put(session.getId(), link);
    }
    try {
      service.start(owner, id);
      link.worker =
          client
              .newWebSocketBuilder()
              .header("Authorization", "Bearer " + token)
              .connectTimeout(Duration.ofSeconds(8))
              .buildAsync(
                  URI.create(url),
                  new java.net.http.WebSocket.Listener() {
                    final StringBuilder message = new StringBuilder();

                    public void onOpen(java.net.http.WebSocket w) {
                      w.request(1);
                    }

                    public CompletionStage<?> onText(
                        java.net.http.WebSocket w, CharSequence data, boolean last) {
                      message.append(data);
                      if (message.length() > 500000) {
                        fail(link, "Speech service returned too much text.");
                        return null;
                      }
                      if (last) {
                        try {
                          String raw = message.toString();
                          message.setLength(0);
                          var event = json.readTree(raw);
                          String type = event.path("type").asText();
                          if (type.equals("update") || type.equals("final")) {
                            String confirmed = event.path("confirmed").asText();
                            if (confirmed.length() > 100000)
                              throw new IllegalStateException("Transcript limit");
                            service.progress(
                                owner,
                                id,
                                confirmed,
                                event.path("durationMs").asLong(),
                                event.path("model").asText("whisper"),
                                type.equals("final") ? "COMPLETED" : "RECORDING");
                          }
                          send(link, raw);
                          if (type.equals("final") || type.equals("error")) close(link);
                        } catch (Exception ex) {
                          fail(
                              link,
                              "Transcription stopped unexpectedly. Confirmed text remains in"
                                  + " history.");
                        }
                      }
                      w.request(1);
                      return null;
                    }

                    public CompletionStage<?> onClose(
                        java.net.http.WebSocket w, int status, String reason) {
                      if (!link.closed.get())
                        fail(
                            link,
                            "Speech service disconnected. Confirmed text remains in history.");
                      return null;
                    }

                    public void onError(java.net.http.WebSocket w, Throwable error) {
                      fail(link, "Cannot reach the speech service. Check that the model is ready.");
                    }
                  })
              .get(10, TimeUnit.SECONDS);
      if (link.closed.get()) link.worker.abort();
    } catch (Exception e) {
      fail(link, "Cannot start recording. Check service readiness or create a new recording.");
    }
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage msg) {
    Link l = links.get(session.getId());
    if (l == null || l.closed.get()) return;
    if (l.worker == null || l.stopping) {
      fail(l, "Audio arrived outside an active recording.");
      return;
    }
    int count = msg.getPayloadLength();
    l.bytes += count;
    if (count == 0 || count % 2 != 0 || l.bytes > 16000L * 2 * 600) {
      fail(l, "Recording limit reached (10 minutes). Start a new recording.");
      return;
    }
    try {
      byte[] copy = new byte[count];
      msg.getPayload().get(copy);
      l.worker.sendBinary(ByteBuffer.wrap(copy), true).get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      fail(l, "Audio connection interrupted. Confirmed text remains in history.");
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage msg) {
    Link l = links.get(session.getId());
    if (l == null || l.closed.get()) return;
    try {
      if (!json.readTree(msg.getPayload()).path("type").asText().equals("stop")
          || l.stopping
          || l.worker == null) throw new IllegalArgumentException();
      l.stopping = true;
      l.worker.sendText("{\"type\":\"stop\"}", true).get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      fail(l, "Invalid recording command or connection failure.");
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    var l = links.get(session.getId());
    if (l != null) close(l);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable error) {
    var l = links.get(session.getId());
    if (l != null) fail(l, "Browser connection interrupted.");
  }

  @Scheduled(fixedDelay = 15000)
  public void expire() {
    for (Link l : links.values())
      if (System.currentTimeMillis() - l.opened > 720000)
        fail(l, "Recording timed out. Confirmed text remains in history.");
  }

  private void send(Link l, String text) throws Exception {
    if (l.browser.isOpen()) l.browser.sendMessage(new TextMessage(text));
  }

  private void fail(Link l, String message) {
    try {
      send(l, json.writeValueAsString(Map.of("type", "error", "message", message)));
    } catch (Exception ignored) {
    } finally {
      close(l);
    }
  }

  private void close(Link l) {
    if (!l.closed.compareAndSet(false, true)) return;
    try {
      service.interrupt(l.owner, l.id);
    } catch (Exception ignored) {
    }
    links.remove(l.browser.getId());
    owners.remove(l.owner);
    if (l.worker != null) l.worker.abort();
    try {
      if (l.browser.isOpen()) l.browser.close();
    } catch (Exception ignored) {
    }
  }
}
