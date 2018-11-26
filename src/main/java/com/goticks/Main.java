package com.goticks;


import akka.NotUsed;
import akka.actor.ActorSystem;
import com.typesafe.config.Config;

import java.util.concurrent.CompletionStage;

import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.server.AllDirectives;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;

import com.typesafe.config.ConfigFactory;


public class Main extends AllDirectives {
  public static void main(String[] args) throws Exception {

    Config config = ConfigFactory.load();
    String host = config.getString("http.host"); // 設定からホスト名とポートを取得
    int port = config.getInt("http.port");

    final ActorSystem system = ActorSystem.create("go-ticks");

    final Http http = Http.get(system);
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    //In order to access all directives we need an instance where the routes are define.
    RestApi app = new RestApi();

    final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = app.createRoute().flow(system, materializer);
    final CompletionStage<ServerBinding> binding = http.bindAndHandle(routeFlow,
        ConnectHttp.toHost(host, port), materializer);

    final LoggingAdapter log = Logging.getLogger(system, Main.class);


    log.info("Server online at http://{}:{}/\nPress RETURN to stop...", host, port);

    System.in.read(); // let it run until user presses return

    log.info("presses return...");

    binding
        .thenCompose(ServerBinding::unbind) // trigger unbinding from the port
        .thenAccept(unbound -> system.terminate()); // and shutdown when done

  }

}
