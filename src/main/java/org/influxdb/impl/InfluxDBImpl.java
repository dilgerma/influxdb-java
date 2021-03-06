package org.influxdb.impl;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.squareup.okhttp.OkHttpClient;
import org.influxdb.InfluxDB;
import org.influxdb.dto.*;
import org.influxdb.impl.BatchProcessor.BatchEntry;
import retrofit.RestAdapter;
import retrofit.client.Client;
import retrofit.client.Header;
import retrofit.client.OkClient;
import retrofit.client.Response;
import retrofit.mime.TypedString;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementation of a InluxDB API.
 *
 * @author stefan.majer [at] gmail.com
 */
public class InfluxDBImpl implements InfluxDB {
    private String defaultRetentionPolicy = "default";
    private final String username;
    private final String password;
    private final RestAdapter restAdapter;
    private final InfluxDBService influxDBService;
    private BatchProcessor batchProcessor;
    private final AtomicBoolean batchEnabled = new AtomicBoolean(false);
    private final AtomicLong writeCount = new AtomicLong();
    private final AtomicLong unBatchedCount = new AtomicLong();
    private final AtomicLong batchedCount = new AtomicLong();
    private LogLevel logLevel = LogLevel.NONE;

    /**
     * Constructor which should only be used from the InfluxDBFactory.
     *
     * @param url      the url where the influxdb is accessible.
     * @param username the user to connect.
     * @param password the password for this user.
     */
    public InfluxDBImpl(final String url, final String username, final String password) {
        super();
        this.username = username;
        this.password = password;
        Client client = new OkClient(new OkHttpClient());
        this.restAdapter = new RestAdapter.Builder()
                .setEndpoint(url)
                .setErrorHandler(new InfluxDBErrorHandler())
                .setClient(client)
                .build();
        this.influxDBService = this.restAdapter.create(InfluxDBService.class);
    }

    @Override
    public InfluxDB setLogLevel(final LogLevel logLevel) {
        switch (logLevel) {
            case NONE:
                this.restAdapter.setLogLevel(retrofit.RestAdapter.LogLevel.NONE);
                break;
            case BASIC:
                this.restAdapter.setLogLevel(retrofit.RestAdapter.LogLevel.BASIC);
                break;
            case HEADERS:
                this.restAdapter.setLogLevel(retrofit.RestAdapter.LogLevel.HEADERS);
                break;
            case FULL:
                this.restAdapter.setLogLevel(retrofit.RestAdapter.LogLevel.FULL);
                break;
            default:
                break;
        }
        this.logLevel = logLevel;
        return this;
    }

    @Override
    public InfluxDB enableBatch(final int actions, final int flushDuration, final TimeUnit flushDurationTimeUnit) {
        if (this.batchEnabled.get()) {
            return this;
        }
        this.batchProcessor = BatchProcessor
                .builder(this)
                .actions(actions)
                .interval(flushDuration, flushDurationTimeUnit)
                .build();
        this.batchEnabled.set(true);
        return this;
    }

    @Override
    public InfluxDB disableBatch() {
        if (!batchEnabled.get()) {
            return this;
        }
        this.batchEnabled.set(false);
        if (batchProcessor != null) {
            this.batchProcessor.flush();
        }
        if (this.logLevel != LogLevel.NONE) {
            System.out.println(
                    "total writes:" + this.writeCount.get() + " unbatched:" + this.unBatchedCount.get() + "batchPoints:"
                            + this.batchedCount);
        }
        return this;
    }

    public InfluxDB withDefaultRetentionPolicy(String retentionPolicy) {
        Preconditions.checkNotNull(retentionPolicy, "retentionPolicy should not be null");
        this.defaultRetentionPolicy = retentionPolicy;
        return this;
    }

    @Override
    public Pong ping() {
        Stopwatch watch = Stopwatch.createStarted();
        Response response = this.influxDBService.ping();
        List<Header> headers = response.getHeaders();
        String version = "unknown";
        for (Header header : headers) {
            if (null != header.getName() && header.getName().equalsIgnoreCase("X-Influxdb-Version")) {
                version = header.getValue();
            }
        }
        Pong pong = new Pong();
        pong.setVersion(version);
        pong.setResponseTime(watch.elapsed(TimeUnit.MILLISECONDS));
        return pong;
    }

    @Override
    public String version() {
        return ping().getVersion();
    }

    @Override
    public void write(final String database, final String retentionPolicy, final Point point) {

        String retentionPolicyOrDefault = retentionPolicyOrDefault(retentionPolicy);

        if (this.batchEnabled.get()) {
            BatchEntry batchEntry = new BatchEntry(point, database, retentionPolicyOrDefault);
            this.batchProcessor.put(batchEntry);
        } else {
            BatchPoints batchPoints = BatchPoints.database(database).retentionPolicy(retentionPolicyOrDefault).build();
            batchPoints.point(point);
            this.write(batchPoints);
            this.unBatchedCount.incrementAndGet();
        }
        this.writeCount.incrementAndGet();
    }

    private String retentionPolicyOrDefault(String retentionPolicy) {
        return retentionPolicy == null ? defaultRetentionPolicy : retentionPolicy;
    }

    @Override
    public void write(final BatchPoints batchPoints) {
        this.batchedCount.addAndGet(batchPoints.getPoints().size());
        TypedString lineProtocol = new TypedString(batchPoints.lineProtocol());
        this.influxDBService.writePoints(
                this.username,
                this.password,
                batchPoints.getDatabase(),
                batchPoints.getRetentionPolicy(),
                TimeUtil.toTimePrecision(TimeUnit.NANOSECONDS),
                batchPoints.getConsistency().value(),
                lineProtocol);

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public QueryResult query(final Query query) {
        QueryResult response = this.influxDBService
                .query(this.username, this.password, query.getDatabase(), query.getCommand());
        return response;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public QueryResult query(final Query query, final TimeUnit timeUnit) {
        QueryResult response = this.influxDBService
                .query(this.username,
                        this.password,
                        query.getDatabase(),
                        TimeUtil.toTimePrecision(timeUnit),
                        query.getCommand());
        return response;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createDatabase(final String name) {
        Preconditions.checkArgument(!name.contains("-"), "Databasename cant contain -");
        this.influxDBService.query(this.username, this.password, "CREATE DATABASE " + name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteDatabase(final String name) {
        this.influxDBService.query(this.username, this.password, "DROP DATABASE " + name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> describeDatabases() {
        QueryResult result = this.influxDBService.query(this.username, this.password, "SHOW DATABASES");
        // {"results":[{"series":[{"name":"databases","columns":["name"],"values":[["mydb"]]}]}]}
        // Series [name=databases, columns=[name], values=[[mydb], [unittest_1433605300968]]]
        List<List<Object>> databaseNames = result.getResults().get(0).getSeries().get(0).getValues();
        List<String> databases = Lists.newArrayList();
        if (databaseNames != null) {
            for (List<Object> database : databaseNames) {
                databases.add(database.get(0).toString());
            }
        }
        return databases;
    }

}
