package com.project.weatherApp;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.mysqlclient.MySQLPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import java.util.LinkedHashMap;
import io.github.cdimascio.dotenv.Dotenv;

// :: table columns -> id(pri) name lat lon country_code weather temperature

public class MainVerticle extends AbstractVerticle {
  private final String url = "api.openweathermap.org";
  // TODO: key storage
  private String API_KEY;

  // private JsonObject globalJson;
//  public final JsonObject fetch(String requestUrl){
//    WebClient client2 = WebClient.create(vertx);
//    client2.get(url, requestUrl)
//      .send().onSuccess(response -> {
//        System.out.println("Received response:" + response.statusCode());
//        JsonObject jsonBody = null;
//        try {
//          jsonBody = (JsonObject) Json.decodeValue(response.body());
//        } catch (DecodeException e) {
//          System.out.println("error decoding response as JSON: " + e.getMessage());
//        }
//        assert jsonBody != null;
//      });
//  }

  @Override
  public void start(Promise<Void> startPromise) throws Exception {
    Router router = Router.router(vertx);
    WebClient client = WebClient.create(vertx);
    Dotenv dotenv = Dotenv.configure()
      .directory("/home/ishankumark/Documents/code/vertx/src/main/resources")
      .filename("env")
      .load();

    API_KEY = dotenv.get("API_KEY");


    MySQLConnectOptions connectOptions = new MySQLConnectOptions()
      .setPort(dotenv.get("SQL_PORT") == null ? 3306 : Integer.parseInt(dotenv.get("SQL_PORT")))
      .setHost(dotenv.get("SQL_HOST"))
      .setDatabase("weatherdb")
      .setUser(dotenv.get("SQL_USER"))
      .setPassword(dotenv.get("SQL_PASSWORD"))
      .setConnectTimeout(3000);

    // Pool options
    PoolOptions poolOptions = new PoolOptions().setMaxSize(5);

    // Create the client from pool
    SqlClient sqlClient = MySQLPool.client(vertx, connectOptions, poolOptions);


    vertx.createHttpServer().requestHandler(router).listen(8080, httpServerAsyncResult -> {
      if (httpServerAsyncResult.succeeded()){
        System.out.println("Starting server at port 8080");
        startPromise.complete();
      }
      else{
        startPromise.fail("Http server start failed: " + httpServerAsyncResult.cause());
      }
    });

    router.get("/data/:lat/:lon").handler(
      context -> {
        String queryLatitude = context.pathParam("lat");
        String queryLongitude = context.pathParam("lon");
        System.out.printf("%s %s %n", queryLatitude, queryLongitude);
        String requestUrl = String.format("/data/2.5/weather?lat=%s&lon=%s&appid=%s", queryLatitude, queryLongitude, API_KEY);
        System.out.println(" +++ " + requestUrl);
        client.get(url, requestUrl)
          .send().onSuccess(response -> {
            System.out.println("Received response:" + response.statusCode());
            JsonObject jsonBody = null;
            try{
              jsonBody = (JsonObject) Json.decodeValue(response.body());
            }
            catch (DecodeException e){
              System.out.println("error decoding response as JSON: " + e.getMessage());
            }
            assert jsonBody != null;
            // DEBUG
            System.out.println("Json: " + jsonBody.encodePrettily());

            LinkedHashMap<String, String> dataMap = encodeUtil.getDataMap(jsonBody);
            // DEBUG
            String deugOutput = String.format("%s %s %s %s %s %s %s%n", dataMap.get("id"), dataMap.get("name"),
              dataMap.get("lat"), dataMap.get("lon"), dataMap.get("country_code"), dataMap.get("weather"), dataMap.get("temp"));
            System.out.printf(deugOutput);
            // Insert SQL Query
            sqlClient.query(String.format("INSERT INTO weather VALUES (%s, '%s', %s, %s, '%s', '%s', %s)", dataMap.get("id"), dataMap.get("name"),
              dataMap.get("lat"), dataMap.get("lon"), dataMap.get("country_code"), dataMap.get("weather"), dataMap.get("temp"))).execute(
              ar -> {
                if (ar.succeeded()){
                  RowSet<Row> result = ar.result();
                  System.out.println("Success: " + result.size() + " ");
                }
                else{
                  System.out.println("Failure Sql(get): " + ar.cause().getMessage());
                }
              }
            );
            // test with the use of buffer
            // JsonObject jsonResult = new JsonObject((Buffer) dataMap);
            //response
            context.response().setStatusCode(200).end(encodeUtil.mapToJson(dataMap).encodePrettily());
          })
          .onFailure(err-> System.out.println("Something went wrong: " + err.getMessage()));
      }
    );


    // Testing re-routing
//    router.get("/data/re/:lat/:lon").handler(context -> {
//      context.reroute(HttpMethod.GET, String.format("/data/%s/%s",));
//    });

    // TODO: check for timeout
    // router.get("/data/update/:lat/:lon").handler(TimeoutHandler.create(3000));
    router.put("/data/update/:lat/:lon").handler(context -> {
      String queryLatitude = context.pathParam("lat");
      String queryLongitude = context.pathParam("lon");
      System.out.printf("put query: %s, %s%n", queryLatitude, queryLongitude);
      String queryString = String.format("SELECT * FROM weather WHERE lat=%s AND lon=%s", queryLatitude, queryLongitude);
      sqlClient.query(queryString).execute(
        ar -> {
          if (ar.succeeded()){
            RowSet<Row> result = ar.result();
            System.out.println("Success: " + result.size());
            if (result.size() == 0){
              context.reroute(HttpMethod.GET, String.format("/data/%s/%s", queryLatitude, queryLongitude));
            }
            else{
              String requestUrl = String.format("/data/2.5/weather?lat=%s&lon=%s&appid=%s", queryLatitude, queryLongitude, API_KEY);
              client.get(url, requestUrl)
                .send().onSuccess(response -> {
                  System.out.println("Received response:" + response.statusCode());
                  JsonObject jsonBody = null;
                  try{
                    jsonBody = (JsonObject) Json.decodeValue(response.body());
                  }
                  catch (DecodeException e){
                    System.out.println("error decoding response as JSON: " + e.getMessage());
                  }
                  assert jsonBody != null;
                  System.out.println("Json body after fetching: " + jsonBody.toString());
                  LinkedHashMap<String, String> dataMap = encodeUtil.getDataMap(jsonBody);
                  String updateQuery = String.format("UPDATE weather SET id=%s, name='%s', lat=%s, lon=%s, country_code='%s', weather='%s', temp=%s WHERE (lat=%s AND lon=%s)",
                    dataMap.get("id"), dataMap.get("name"), dataMap.get("lat"), dataMap.get("lon"), dataMap.get("country_code"), dataMap.get("weather"),
                    dataMap.get("temp"), dataMap.get("lat"), dataMap.get("lon"));
                  System.out.println(updateQuery);
                  sqlClient.query(updateQuery).execute(ar2 -> {
                    if (ar2.succeeded()){
                      System.out.println("Successfully updated!");
                      context.response().setStatusCode(200).end(encodeUtil.mapToJson(dataMap).encodePrettily()); // json string
                    }
                    else{
                      String errorStr = String.format("Error updating value at (%s, %s): %s%n", queryLatitude, queryLongitude, ar.cause().getMessage());
                      System.out.printf(errorStr);
                      context.response().setStatusCode(400).end(encodeUtil.stringToJson(errorStr).encodePrettily());
                    }
                  });
                });
            }
          }
          else {
            System.out.println("Failure Sql: " + ar.cause().getMessage());
            context.response().setStatusCode(500).end(encodeUtil.stringToJson(ar.cause().getMessage()).encodePrettily());
          }
        });
    });
    router.get("/data/search/:lat/:lon").handler(context -> {
      String queryLatitude = context.pathParam("lat");
      String queryLongitude = context.pathParam("lon");
      String queryString = String.format("SELECT * FROM weather WHERE lat=%s AND lon=%s", queryLatitude, queryLongitude);
      System.out.println(queryString);
      sqlClient.query(queryString).execute(
        ar -> {
          if (ar.succeeded()){
            RowSet<Row> result = ar.result();
            System.out.println("Success: " + result.size());
            if (result.size() == 0){
              context.response().setStatusCode(400).end("Data not found!");
            }
            else{
              JsonArray jsonArray = new JsonArray();
              for (Row row : result) {
                JsonObject jsonObject = row.toJson();
                jsonArray.add(jsonObject);
              }
              context.response().setStatusCode(200).end(jsonArray.encodePrettily()); // json string
            }
          }
          else{
            System.out.println("Failure Sql: " + ar.cause().getMessage());
          }
        });
    });
  }
}