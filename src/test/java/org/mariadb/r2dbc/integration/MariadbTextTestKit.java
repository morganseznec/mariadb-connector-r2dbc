package org.mariadb.r2dbc.integration;

import io.r2dbc.spi.Blob;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.test.TestKit;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.MariaDbDataSource;
import org.mariadb.r2dbc.MariadbConnectionConfiguration;
import org.mariadb.r2dbc.MariadbConnectionFactory;
import org.mariadb.r2dbc.TestConfiguration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.support.AbstractLobCreatingPreparedStatementCallback;
import org.springframework.jdbc.support.lob.DefaultLobHandler;
import org.springframework.jdbc.support.lob.LobCreator;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

public class MariadbTextTestKit implements TestKit<String> {
  private static DataSource jdbcDatasource;

  static {
    String connString =
        String.format(
            "jdbc:mariadb://%s:%s/%s?user=%s&password=%s",
            TestConfiguration.host,
            TestConfiguration.port,
            TestConfiguration.database,
            TestConfiguration.username,
            TestConfiguration.password);
    try {
      jdbcDatasource = new MariaDbDataSource(connString);
    } catch (SQLException e) {
      throw new IllegalArgumentException(
          String.format("wrong initialization with %s", connString), e);
    }
  }

  @Override
  public ConnectionFactory getConnectionFactory() {
    try {
      MariadbConnectionConfiguration confMulti =
          TestConfiguration.defaultBuilder.clone().allowMultiQueries(true).build();
      return new MariadbConnectionFactory(confMulti);
    } catch (CloneNotSupportedException e) {
      throw new IllegalStateException("Unexpected error");
    }
  }

  @Override
  public String getPlaceholder(int i) {
    return ":v" + i;
  }

  @Override
  public String getIdentifier(int i) {
    return "v" + i;
  }

  @Override
  public JdbcOperations getJdbcOperations() {
    return new JdbcTemplate(MariadbTextTestKit.jdbcDatasource);
  }

  @Override
  public String doGetSql(TestStatement statement) {
    switch (statement) {
      case CREATE_TABLE_AUTOGENERATED_KEY:
        return TestStatement.CREATE_TABLE_AUTOGENERATED_KEY
            .getSql()
            .replaceAll("IDENTITY", "PRIMARY KEY AUTO_INCREMENT");
      case INSERT_VALUE_AUTOGENERATED_KEY:
      case INSERT_VALUE100:
        return "INSERT INTO test(test_value) VALUES (100)";
      case INSERT_VALUE200:
        return "INSERT INTO test(test_value) VALUES (200)";
      default:
        return statement.getSql();
    }
  }

  @Override
  public String clobType() {
    return "TEXT";
  }

  @Test
  public void blobSelect() {
    getJdbcOperations()
        .execute(
            expand(TestStatement.INSERT_BLOB_VALUE_PLACEHOLDER, "?"),
            new AbstractLobCreatingPreparedStatementCallback(new DefaultLobHandler()) {

              @Override
              protected void setValues(PreparedStatement ps, LobCreator lobCreator)
                  throws SQLException {
                lobCreator.setBlobAsBytes(ps, 1, "test-value".getBytes(StandardCharsets.UTF_8));
              }
            });

    // BLOB as ByteBuffer
    Flux.usingWhen(
            getConnectionFactory().create(),
            connection ->
                Flux.from(
                        connection
                            .createStatement(expand(TestStatement.SELECT_BLOB_VALUE))
                            .execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> extractColumn(row)))
                    .cast(ByteBuffer.class)
                    .map(
                        buffer -> {
                          byte[] bytes = new byte[buffer.remaining()];
                          buffer.get(bytes);
                          return bytes;
                        }),
            Connection::close)
        .as(StepVerifier::create)
        .expectNextMatches(
            actual -> {
              ByteBuffer expected = ByteBuffer.wrap("test-value".getBytes(StandardCharsets.UTF_8));
              return Arrays.equals(expected.array(), actual);
            })
        .verifyComplete();

    // BLOB as Blob
    Flux.usingWhen(
            getConnectionFactory().create(),
            connection ->
                Flux.from(
                        connection
                            .createStatement(expand(TestStatement.SELECT_BLOB_VALUE))
                            .execute())
                    .flatMap(
                        result ->
                            Flux.usingWhen(
                                result.map((row, rowMetadata) -> extractColumn(row, Blob.class)),
                                blob -> Flux.from(blob.stream()).reduce(ByteBuffer::put),
                                Blob::discard)),
            Connection::close)
        .as(StepVerifier::create)
        .expectNextMatches(
            actual -> {
              ByteBuffer expected = StandardCharsets.UTF_8.encode("test-value");
              return actual.compareTo(expected) == 0;
            })
        .verifyComplete();
  }
}
