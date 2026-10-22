package com.voiceline;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.*;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocket
public class SocketConfig implements WebSocketConfigurer {
  private final SpeechSocket socket;
  private final String origin;

  public SocketConfig(SpeechSocket socket, @Value("${app.origin}") String origin) {
    this.socket = socket;
    this.origin = origin;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry r) {
    r.addHandler(socket, "/api/transcriptions/*/stream")
        .setAllowedOrigins(origin)
        .addInterceptors(
            new HandshakeInterceptor() {
              public boolean beforeHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler handler,
                  Map<String, Object> attributes) {
                if (!(request instanceof ServletServerHttpRequest servlet)) return false;
                var session = servlet.getServletRequest().getSession(false);
                if (session == null || session.getAttribute("owner") == null) return false;
                attributes.put("owner", session.getAttribute("owner"));
                return true;
              }

              public void afterHandshake(
                  ServerHttpRequest r, ServerHttpResponse s, WebSocketHandler h, Exception e) {}
            });
  }
}
