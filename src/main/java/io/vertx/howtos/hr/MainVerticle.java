package io.vertx.howtos.hr;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.HttpServer;
import io.vertx.mutiny.ext.web.Router;
import io.vertx.mutiny.ext.web.RoutingContext;
import io.vertx.mutiny.ext.web.handler.BodyHandler;
import org.hibernate.reactive.mutiny.Mutiny;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.persistence.Persistence;
import java.util.List;
import java.util.Map;

// tag::preamble[]
public class MainVerticle extends AbstractVerticle {

  private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);
  private Mutiny.SessionFactory emf;  // <1>

  @Override
  public Uni<Void> asyncStart() {
// end::preamble[]

    // tag::hr-start[]
    Uni<Void> startHibernate = Uni.createFrom().deferred(() -> {
      var pgPort = config().getInteger("pgPort", 5432);
      var props = Map.of("javax.persistence.jdbc.url", "jdbc:postgresql://localhost:" + pgPort + "/postgres");  // <1>

      emf = Persistence
        .createEntityManagerFactory("pg-demo", props)
        .unwrap(Mutiny.SessionFactory.class);

      return Uni.createFrom().voidItem();
    });

    startHibernate = vertx.executeBlocking(startHibernate)  // <2>
      .onItem().invoke(() -> logger.info("✅ Hibernate Reactive is ready"));
    // end::hr-start[]

    // tag::routing[]
    Router router = Router.router(vertx);

    BodyHandler bodyHandler = BodyHandler.create();
    router.post().handler(bodyHandler::handle);

    router.get("/products").respond(this::listProducts);
    router.get("/products/:id").respond(this::getProduct);
    router.post("/products").respond(this::createProduct);
    // end::routing[]

    // tag::async-start[]
    Uni<HttpServer> startHttpServer = vertx.createHttpServer()
      .requestHandler(router::handle)
      .listen(8080)
      .onItem().invoke(() -> logger.info("✅ HTTP server listening on port 8080"));

    return Uni.combine().all().unis(startHibernate, startHttpServer).discardItems();  // <1>
    // end::async-start[]
  }

  // tag::crud-methods[]
  private Uni<List<Product>> listProducts(RoutingContext ctx) {
    return emf.withSession(session -> session
      .createQuery("from Product", Product.class)
      .getResultList());
  }

  private Uni<Product> getProduct(RoutingContext ctx) {
    long id = Long.parseLong(ctx.pathParam("id"));
    return emf.withSession(session -> session
      .find(Product.class, id))
      .onItem().ifNull().continueWith(Product::new);
  }

  private Uni<Product> createProduct(RoutingContext ctx) {
    Product product = ctx.getBodyAsJson().mapTo(Product.class);
    return emf.withSession(session -> session.
      persist(product)
      .chain(session::flush)
      .replaceWith(product));
  }
  // end::crud-methods[]

  public static void main(String[] args) {

    long startTime = System.currentTimeMillis();

    logger.info("🚀 Starting a PostgreSQL container");

    // tag::tc-start[]
    PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:11-alpine")
      .withDatabaseName("postgres")
      .withUsername("postgres")
      .withPassword("vertx-in-action");

    postgreSQLContainer.start();
    // end::tc-start[]

    long tcTime = System.currentTimeMillis();

    logger.info("🚀 Starting Vert.x");

    // tag::vertx-start[]
    Vertx vertx = Vertx.vertx();

    DeploymentOptions options = new DeploymentOptions().setConfig(new JsonObject()
      .put("pgPort", postgreSQLContainer.getMappedPort(5432))); // <1>

    vertx.deployVerticle(MainVerticle::new, options).subscribe().with(  // <2>
      ok -> {
        long vertxTime = System.currentTimeMillis();
        logger.info("✅ Deployment success");
        logger.info("💡 PostgreSQL container started in {}ms", (tcTime - startTime));
        logger.info("💡 Vert.x app started in {}ms", (vertxTime - tcTime));
      },
      err -> logger.error("🔥 Deployment failure", err));
    // end::vertx-start[]
  }
}
