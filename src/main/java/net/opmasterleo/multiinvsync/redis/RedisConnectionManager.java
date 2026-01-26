package net.opmasterleo.multiinvsync.redis;

import java.util.logging.Level;
import java.util.logging.Logger;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

/**
 * Manages Redis connection pooling for high-performance, thread-safe access.
 * Uses Jedis connection pool to minimize overhead and support concurrent operations.
 */
public class RedisConnectionManager {
    
    private final Logger logger;
    private JedisPool jedisPool;
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final int timeout;
    private volatile boolean connected = false;
    
    public RedisConnectionManager(Logger logger, String host, int port, String password, int database, int timeout) {
        this.logger = logger;
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
        this.timeout = timeout;
    }
    
    /**
     * Initialize connection pool with optimized settings for inventory sync.
     */
    public boolean connect() {
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10); // Reduced for efficiency
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(2);
            poolConfig.setTestOnBorrow(false); // Disabled for speed
            poolConfig.setTestWhileIdle(false); // Disabled for speed
            poolConfig.setBlockWhenExhausted(true);
            poolConfig.setMaxWaitMillis(2000); // Faster timeout
            poolConfig.setJmxEnabled(false); // Disable JMX overhead
            
            if (password != null && !password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, password, database);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, null, database);
            }
            
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
                connected = true;
                logger.info("Connected to Redis successfully at " + host + ":" + port + " (database " + database + ")");
                return true;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not connect to Redis server", e);
            connected = false;
            return false;
        }
    }
    
    /**
     * Execute a Redis command with automatic resource management.
     * Handles connection failures gracefully.
     */
    public <T> T execute(RedisCommand<T> command) {
        if (!connected || jedisPool == null) {
            return null;
        }
        
        try (Jedis jedis = jedisPool.getResource()) {
            return command.execute(jedis);
        } catch (JedisException e) {
            logger.warning("Redis command failed: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Execute command without returning value (for void operations).
     */
    public void executeVoid(RedisCommand<Void> command) {
        execute(command);
    }
    
    /**
     * Get a Jedis resource directly for pub/sub operations.
     * IMPORTANT: Caller must close the resource!
     */
    public Jedis getResource() {
        if (!connected || jedisPool == null) {
            throw new IllegalStateException("Redis not connected");
        }
        return jedisPool.getResource();
    }
    
    /**
     * Shutdown connection pool and close all connections.
     */
    public void shutdown() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            connected = false;
            logger.info("Disconnected from Redis");
        }
    }
    
    public boolean isConnected() {
        return connected && jedisPool != null && !jedisPool.isClosed();
    }
    
    /**
     * Functional interface for Redis commands.
     */
    @FunctionalInterface
    public interface RedisCommand<T> {
        T execute(Jedis jedis);
    }
}
