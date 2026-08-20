#include <jni.h>

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include <xquic/xqc_errno.h>
#include <xquic/xquic.h>

#define XQB_LOG_TAG "VideoStreamer-xquic"
#define XQB_RECV_BUFFER_SIZE (1024U * 1024U)
#define XQB_MAX_PENDING_SEND (8U * 1024U * 1024U)
#define XQB_PACKET_BUFFER_SIZE 65536U
#define XQB_MAX_ERROR_LENGTH 256U

typedef struct xqb_connection_s xqb_connection_t;
typedef struct xqb_stream_s xqb_stream_t;

typedef struct xqb_send_chunk_s {
    struct xqb_send_chunk_s *next;
    size_t size;
    size_t offset;
    unsigned char data[];
} xqb_send_chunk_t;

struct xqb_stream_s {
    xqb_connection_t *connection;
    jlong handle;
    xqc_stream_t *xqc_stream;
    int bidirectional;
    int opening;
    int open_done;
    int open_error;
    int closed;
    int local_fin_requested;
    int local_fin_sent;
    int remote_fin;
    int read_cancelled;
    int needs_drain;
    char error_message[XQB_MAX_ERROR_LENGTH];

    xqb_send_chunk_t *send_head;
    xqb_send_chunk_t *send_tail;
    size_t pending_send;

    unsigned char *recv_buffer;
    size_t recv_head;
    size_t recv_size;

    pthread_cond_t condition;
    xqb_stream_t *next;
    xqb_stream_t *registry_next;
};

struct xqb_connection_s {
    jlong handle;
    xqc_engine_t *engine;
    xqc_connection_t *xqc_connection;
    xqc_cid_t cid;
    int has_cid;

    int socket_fd;
    int wake_pipe[2];
    struct sockaddr_storage peer_addr;
    socklen_t peer_addr_len;
    struct sockaddr_storage local_addr;
    socklen_t local_addr_len;

    char *host;
    char *alpn;
    int connect_timeout_ms;
    int idle_timeout_ms;

    pthread_t worker;
    int worker_started;
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    uint64_t timer_due_us;
    int handshake_done;
    int closed;
    int stopping;
    int destroying;
    int active_calls;
    char error_message[XQB_MAX_ERROR_LENGTH];

    xqb_stream_t *streams;
    xqb_connection_t *registry_next;
};

static pthread_mutex_t g_registry_mutex = PTHREAD_MUTEX_INITIALIZER;
static xqb_connection_t *g_connections = NULL;
static xqb_stream_t *g_streams = NULL;
static uint64_t g_next_handle = 1;

static xqc_usec_t
xqb_monotonic_us(void)
{
    struct timespec value;
    clock_gettime(CLOCK_MONOTONIC, &value);
    return (xqc_usec_t)value.tv_sec * 1000000ULL + (xqc_usec_t)value.tv_nsec / 1000ULL;
}

static xqc_usec_t
xqb_realtime_us(void)
{
    struct timespec value;
    clock_gettime(CLOCK_REALTIME, &value);
    return (xqc_usec_t)value.tv_sec * 1000000ULL + (xqc_usec_t)value.tv_nsec / 1000ULL;
}

static void
xqb_deadline_after_ms(struct timespec *deadline, int timeout_ms)
{
    clock_gettime(CLOCK_REALTIME, deadline);
    deadline->tv_sec += timeout_ms / 1000;
    deadline->tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
    if (deadline->tv_nsec >= 1000000000L) {
        deadline->tv_sec++;
        deadline->tv_nsec -= 1000000000L;
    }
}

static void
xqb_throw_io(JNIEnv *env, const char *message)
{
    jclass exception_class = (*env)->FindClass(env, "java/io/IOException");
    if (exception_class != NULL) {
        (*env)->ThrowNew(env, exception_class, message != NULL ? message : "xquic 发生未知错误");
    }
}

static void
xqb_set_nonblocking(int fd)
{
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) {
        (void)fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    }
    flags = fcntl(fd, F_GETFD, 0);
    if (flags >= 0) {
        (void)fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
    }
}

static void
xqb_wake(xqb_connection_t *connection)
{
    if (connection == NULL || connection->wake_pipe[1] < 0) {
        return;
    }
    const unsigned char byte = 1;
    ssize_t result;
    do {
        result = write(connection->wake_pipe[1], &byte, sizeof(byte));
    } while (result < 0 && errno == EINTR);
}

static void
xqb_drain_wake_pipe(xqb_connection_t *connection)
{
    unsigned char buffer[64];
    while (read(connection->wake_pipe[0], buffer, sizeof(buffer)) > 0) {
    }
}

static void
xqb_signal_streams_locked(xqb_connection_t *connection)
{
    for (xqb_stream_t *stream = connection->streams; stream != NULL; stream = stream->next) {
        pthread_cond_broadcast(&stream->condition);
    }
    pthread_cond_broadcast(&connection->condition);
}

static void
xqb_set_connection_error_locked(xqb_connection_t *connection, const char *message)
{
    if (connection->error_message[0] == '\0' && message != NULL && message[0] != '\0') {
        snprintf(connection->error_message, sizeof(connection->error_message), "%s", message);
    }
    connection->closed = 1;
    for (xqb_stream_t *stream = connection->streams; stream != NULL; stream = stream->next) {
        stream->closed = 1;
    }
    xqb_signal_streams_locked(connection);
}

static void
xqb_set_connection_error(xqb_connection_t *connection, const char *message)
{
    pthread_mutex_lock(&connection->mutex);
    xqb_set_connection_error_locked(connection, message);
    pthread_mutex_unlock(&connection->mutex);
    xqb_wake(connection);
}

static void
xqb_set_stream_error_locked(xqb_stream_t *stream, const char *message)
{
    if (stream->error_message[0] == '\0' && message != NULL && message[0] != '\0') {
        snprintf(stream->error_message, sizeof(stream->error_message), "%s", message);
    }
    stream->closed = 1;
    pthread_cond_broadcast(&stream->condition);
}

static jlong
xqb_allocate_handle_locked(void)
{
    uint64_t value = g_next_handle++;
    if (value == 0) {
        value = g_next_handle++;
    }
    return (jlong)value;
}

static void
xqb_registry_add_connection(xqb_connection_t *connection)
{
    pthread_mutex_lock(&g_registry_mutex);
    connection->handle = xqb_allocate_handle_locked();
    connection->registry_next = g_connections;
    g_connections = connection;
    pthread_mutex_unlock(&g_registry_mutex);
}

static int
xqb_registry_add_stream(xqb_stream_t *stream)
{
    xqb_connection_t *connection = stream->connection;
    int added = 0;
    pthread_mutex_lock(&g_registry_mutex);
    pthread_mutex_lock(&connection->mutex);
    if (!connection->destroying && !connection->stopping && !connection->closed) {
        stream->handle = xqb_allocate_handle_locked();
        stream->registry_next = g_streams;
        g_streams = stream;
        added = 1;
    }
    pthread_mutex_unlock(&connection->mutex);
    pthread_mutex_unlock(&g_registry_mutex);
    return added;
}

static xqb_connection_t *
xqb_registry_acquire_connection(jlong handle)
{
    xqb_connection_t *result = NULL;
    pthread_mutex_lock(&g_registry_mutex);
    for (xqb_connection_t *current = g_connections; current != NULL; current = current->registry_next) {
        if (current->handle == handle) {
            pthread_mutex_lock(&current->mutex);
            if (!current->destroying) {
                current->active_calls++;
                result = current;
            }
            pthread_mutex_unlock(&current->mutex);
            break;
        }
    }
    pthread_mutex_unlock(&g_registry_mutex);
    return result;
}

static xqb_stream_t *
xqb_registry_acquire_stream(jlong handle)
{
    xqb_stream_t *result = NULL;
    pthread_mutex_lock(&g_registry_mutex);
    for (xqb_stream_t *current = g_streams; current != NULL; current = current->registry_next) {
        if (current->handle == handle) {
            xqb_connection_t *connection = current->connection;
            pthread_mutex_lock(&connection->mutex);
            if (!connection->destroying) {
                connection->active_calls++;
                result = current;
            }
            pthread_mutex_unlock(&connection->mutex);
            break;
        }
    }
    pthread_mutex_unlock(&g_registry_mutex);
    return result;
}

static void
xqb_registry_release_connection(xqb_connection_t *connection)
{
    pthread_mutex_lock(&connection->mutex);
    connection->active_calls--;
    pthread_cond_broadcast(&connection->condition);
    pthread_mutex_unlock(&connection->mutex);
}

static xqb_connection_t *
xqb_registry_take_connection(jlong handle)
{
    xqb_connection_t *result = NULL;
    pthread_mutex_lock(&g_registry_mutex);
    xqb_connection_t **connection_link = &g_connections;
    while (*connection_link != NULL) {
        if ((*connection_link)->handle == handle) {
            result = *connection_link;
            *connection_link = result->registry_next;
            result->registry_next = NULL;
            pthread_mutex_lock(&result->mutex);
            result->destroying = 1;
            pthread_mutex_unlock(&result->mutex);
            break;
        }
        connection_link = &(*connection_link)->registry_next;
    }
    if (result != NULL) {
        xqb_stream_t **stream_link = &g_streams;
        while (*stream_link != NULL) {
            if ((*stream_link)->connection == result) {
                xqb_stream_t *removed = *stream_link;
                *stream_link = removed->registry_next;
                removed->registry_next = NULL;
            } else {
                stream_link = &(*stream_link)->registry_next;
            }
        }
    }
    pthread_mutex_unlock(&g_registry_mutex);
    return result;
}

static int
xqb_resolve_and_open_socket(xqb_connection_t *connection, int port, char *error, size_t error_size)
{
    char port_text[16];
    snprintf(port_text, sizeof(port_text), "%d", port);
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_protocol = IPPROTO_UDP;

    struct addrinfo *addresses = NULL;
    int resolve_result = getaddrinfo(connection->host, port_text, &hints, &addresses);
    if (resolve_result != 0) {
        snprintf(error, error_size, "QUIC 主机解析失败：%s", gai_strerror(resolve_result));
        return -1;
    }

    int socket_fd = -1;
    for (struct addrinfo *address = addresses; address != NULL; address = address->ai_next) {
        if (address->ai_addrlen > sizeof(connection->peer_addr)) {
            continue;
        }
        socket_fd = socket(address->ai_family, SOCK_DGRAM, IPPROTO_UDP);
        if (socket_fd < 0) {
            continue;
        }
        xqb_set_nonblocking(socket_fd);
        int buffer_size = 2 * 1024 * 1024;
        (void)setsockopt(socket_fd, SOL_SOCKET, SO_RCVBUF, &buffer_size, sizeof(buffer_size));
        (void)setsockopt(socket_fd, SOL_SOCKET, SO_SNDBUF, &buffer_size, sizeof(buffer_size));

        int bind_result;
        if (address->ai_family == AF_INET6) {
            struct sockaddr_in6 local6;
            memset(&local6, 0, sizeof(local6));
            local6.sin6_family = AF_INET6;
            local6.sin6_addr = in6addr_any;
            bind_result = bind(socket_fd, (struct sockaddr *)&local6, sizeof(local6));
        } else {
            struct sockaddr_in local4;
            memset(&local4, 0, sizeof(local4));
            local4.sin_family = AF_INET;
            local4.sin_addr.s_addr = htonl(INADDR_ANY);
            bind_result = bind(socket_fd, (struct sockaddr *)&local4, sizeof(local4));
        }
        if (bind_result != 0) {
            close(socket_fd);
            socket_fd = -1;
            continue;
        }
        memcpy(&connection->peer_addr, address->ai_addr, address->ai_addrlen);
        connection->peer_addr_len = (socklen_t)address->ai_addrlen;
        connection->local_addr_len = sizeof(connection->local_addr);
        if (getsockname(socket_fd, (struct sockaddr *)&connection->local_addr,
                &connection->local_addr_len) != 0) {
            close(socket_fd);
            socket_fd = -1;
            continue;
        }
        break;
    }
    freeaddrinfo(addresses);

    if (socket_fd < 0) {
        snprintf(error, error_size, "QUIC UDP 套接字创建失败：%s", strerror(errno));
        return -1;
    }
    connection->socket_fd = socket_fd;
    return 0;
}

static void
xqb_log_write(xqc_log_level_t level, const void *buffer, size_t size, void *engine_user_data)
{
    (void)engine_user_data;
    static const char no_early_data[] = "early data is not enabled";
    /* 未配置会话恢复时这是正常状态，xquic 上游却按 error 输出，避免污染实机日志。 */
    if (size >= sizeof(no_early_data) - 1
        && memmem(buffer, size, no_early_data, sizeof(no_early_data) - 1) != NULL) {
        return;
    }
    int priority = (level == XQC_LOG_FATAL || level == XQC_LOG_ERROR)
        ? ANDROID_LOG_ERROR : ANDROID_LOG_DEBUG;
    int safe_size = size > 2048U ? 2048 : (int)size;
    __android_log_print(priority, XQB_LOG_TAG, "%.*s", safe_size, (const char *)buffer);
}

static void
xqb_set_event_timer(xqc_usec_t wake_after, void *engine_user_data)
{
    xqb_connection_t *connection = (xqb_connection_t *)engine_user_data;
    uint64_t now = xqb_monotonic_us();
    pthread_mutex_lock(&connection->mutex);
    connection->timer_due_us = now + (uint64_t)wake_after;
    pthread_mutex_unlock(&connection->mutex);
    xqb_wake(connection);
}

static ssize_t
xqb_write_socket(const unsigned char *buffer, size_t size, const struct sockaddr *peer_addr,
    socklen_t peer_addr_len, void *connection_user_data)
{
    xqb_connection_t *connection = (xqb_connection_t *)connection_user_data;
    if (connection == NULL || connection->socket_fd < 0) {
        return XQC_SOCKET_ERROR;
    }
    const struct sockaddr *destination = peer_addr != NULL
        ? peer_addr : (const struct sockaddr *)&connection->peer_addr;
    socklen_t destination_len = peer_addr != NULL ? peer_addr_len : connection->peer_addr_len;
    ssize_t result;
    do {
        result = sendto(connection->socket_fd, buffer, size, 0, destination, destination_len);
    } while (result < 0 && errno == EINTR);
    if (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        return XQC_SOCKET_EAGAIN;
    }
    if (result < 0 || (size_t)result != size) {
        return XQC_SOCKET_ERROR;
    }
    return result;
}

static void
xqb_save_token(const unsigned char *token, uint32_t token_len, void *connection_user_data)
{
    (void)token;
    (void)token_len;
    (void)connection_user_data;
}

static void
xqb_save_string(const char *data, size_t data_len, void *connection_user_data)
{
    (void)data;
    (void)data_len;
    (void)connection_user_data;
}

static int
xqb_verify_certificate(const unsigned char *certificates[], const size_t certificate_lengths[],
    size_t certificate_count, void *connection_user_data)
{
    (void)certificates;
    (void)certificate_lengths;
    (void)certificate_count;
    (void)connection_user_data;
    /* LiveSuite 使用运行时自签名证书，与旧 Kwik 的 noServerCertificateCheck 行为一致。 */
    return 0;
}

static void
xqb_update_cid(xqc_connection_t *xqc_connection, const xqc_cid_t *retired_cid,
    const xqc_cid_t *new_cid, void *connection_user_data)
{
    (void)xqc_connection;
    (void)retired_cid;
    xqb_connection_t *connection = (xqb_connection_t *)connection_user_data;
    if (connection == NULL || new_cid == NULL) {
        return;
    }
    pthread_mutex_lock(&connection->mutex);
    memcpy(&connection->cid, new_cid, sizeof(connection->cid));
    connection->has_cid = 1;
    pthread_mutex_unlock(&connection->mutex);
}

static int
xqb_connection_create_notify(xqc_connection_t *xqc_connection, const xqc_cid_t *cid,
    void *connection_user_data, void *connection_protocol_data)
{
    (void)connection_protocol_data;
    xqb_connection_t *connection = (xqb_connection_t *)connection_user_data;
    pthread_mutex_lock(&connection->mutex);
    connection->xqc_connection = xqc_connection;
    if (cid != NULL) {
        memcpy(&connection->cid, cid, sizeof(connection->cid));
        connection->has_cid = 1;
    }
    pthread_mutex_unlock(&connection->mutex);
    return XQC_OK;
}

static int
xqb_connection_close_notify(xqc_connection_t *xqc_connection, const xqc_cid_t *cid,
    void *connection_user_data, void *connection_protocol_data)
{
    (void)cid;
    (void)connection_protocol_data;
    xqb_connection_t *connection = (xqb_connection_t *)connection_user_data;
    xqc_int_t error_code = xqc_connection != NULL ? xqc_conn_get_errno(xqc_connection) : 0;
    char message[XQB_MAX_ERROR_LENGTH];
    message[0] = '\0';
    if (error_code != 0) {
        snprintf(message, sizeof(message), "xquic 连接关闭，错误码：%d", error_code);
    }
    pthread_mutex_lock(&connection->mutex);
    connection->xqc_connection = NULL;
    xqb_set_connection_error_locked(connection, message);
    pthread_mutex_unlock(&connection->mutex);
    return XQC_OK;
}

static void
xqb_handshake_finished(xqc_connection_t *xqc_connection, void *connection_user_data,
    void *connection_protocol_data)
{
    (void)connection_protocol_data;
    xqb_connection_t *connection = (xqb_connection_t *)connection_user_data;
    pthread_mutex_lock(&connection->mutex);
    connection->xqc_connection = xqc_connection;
    connection->handshake_done = 1;
    pthread_cond_broadcast(&connection->condition);
    pthread_mutex_unlock(&connection->mutex);
}

static int xqb_drain_stream(xqb_stream_t *stream);

static int
xqb_stream_read_notify(xqc_stream_t *xqc_stream, void *stream_user_data)
{
    (void)xqc_stream;
    xqb_stream_t *stream = (xqb_stream_t *)stream_user_data;
    if (stream == NULL) {
        return XQC_OK;
    }
    (void)xqb_drain_stream(stream);
    return XQC_OK;
}

static int
xqb_stream_write_notify(xqc_stream_t *xqc_stream, void *stream_user_data)
{
    (void)xqc_stream;
    xqb_stream_t *stream = (xqb_stream_t *)stream_user_data;
    if (stream != NULL) {
        xqb_wake(stream->connection);
    }
    return XQC_OK;
}

static int
xqb_stream_create_notify(xqc_stream_t *xqc_stream, void *stream_user_data)
{
    (void)xqc_stream;
    xqb_stream_t *stream = (xqb_stream_t *)stream_user_data;
    (void)stream;
    return XQC_OK;
}

static int
xqb_stream_close_notify(xqc_stream_t *xqc_stream, void *stream_user_data)
{
    (void)xqc_stream;
    xqb_stream_t *stream = (xqb_stream_t *)stream_user_data;
    if (stream == NULL) {
        return XQC_OK;
    }
    xqb_connection_t *connection = stream->connection;
    pthread_mutex_lock(&connection->mutex);
    stream->xqc_stream = NULL;
    stream->closed = 1;
    pthread_cond_broadcast(&stream->condition);
    pthread_mutex_unlock(&connection->mutex);
    return XQC_OK;
}

static int
xqb_initialize_engine(xqb_connection_t *connection, char *error, size_t error_size)
{
    xqc_config_t engine_config;
    if (xqc_engine_get_default_config(&engine_config, XQC_ENGINE_CLIENT) != XQC_OK) {
        snprintf(error, error_size, "读取 xquic 默认配置失败");
        return -1;
    }
    engine_config.cfg_log_level = XQC_LOG_ERROR;
    engine_config.cfg_log_event = 0;
    engine_config.sendmmsg_on = 0;

    xqc_engine_ssl_config_t engine_ssl_config;
    memset(&engine_ssl_config, 0, sizeof(engine_ssl_config));

    xqc_engine_callback_t engine_callbacks;
    memset(&engine_callbacks, 0, sizeof(engine_callbacks));
    engine_callbacks.set_event_timer = xqb_set_event_timer;
    engine_callbacks.realtime_ts = xqb_realtime_us;
    engine_callbacks.monotonic_ts = xqb_monotonic_us;
    engine_callbacks.log_callbacks.xqc_log_write_err = xqb_log_write;
    engine_callbacks.log_callbacks.xqc_log_write_stat = xqb_log_write;
    engine_callbacks.log_callbacks.xqc_qlog_event_write = NULL;

    xqc_transport_callbacks_t transport_callbacks;
    memset(&transport_callbacks, 0, sizeof(transport_callbacks));
    transport_callbacks.write_socket = xqb_write_socket;
    transport_callbacks.conn_update_cid_notify = xqb_update_cid;
    transport_callbacks.save_token = xqb_save_token;
    transport_callbacks.save_session_cb = xqb_save_string;
    transport_callbacks.save_tp_cb = xqb_save_string;
    transport_callbacks.cert_verify_cb = xqb_verify_certificate;

    connection->engine = xqc_engine_create(XQC_ENGINE_CLIENT, &engine_config,
        &engine_ssl_config, &engine_callbacks, &transport_callbacks, connection);
    if (connection->engine == NULL) {
        snprintf(error, error_size, "创建 xquic 引擎失败");
        return -1;
    }

    xqc_app_proto_callbacks_t application_callbacks;
    memset(&application_callbacks, 0, sizeof(application_callbacks));
    application_callbacks.conn_cbs.conn_create_notify = xqb_connection_create_notify;
    application_callbacks.conn_cbs.conn_close_notify = xqb_connection_close_notify;
    application_callbacks.conn_cbs.conn_handshake_finished = xqb_handshake_finished;
    application_callbacks.stream_cbs.stream_read_notify = xqb_stream_read_notify;
    application_callbacks.stream_cbs.stream_write_notify = xqb_stream_write_notify;
    application_callbacks.stream_cbs.stream_create_notify = xqb_stream_create_notify;
    application_callbacks.stream_cbs.stream_close_notify = xqb_stream_close_notify;
    /* xquic 会在 xqc_engine_destroy() 中释放非空 alp_ctx；连接对象由 JNI 自己管理，
     * 且本适配层不使用 conn_proto_data，因此这里必须传 NULL，避免重复释放。 */
    int register_result = xqc_engine_register_alpn(connection->engine, connection->alpn,
        strlen(connection->alpn), &application_callbacks, NULL);
    if (register_result != XQC_OK) {
        snprintf(error, error_size, "注册 QUIC ALPN 失败：%d", register_result);
        return -1;
    }

    xqc_conn_settings_t connection_settings =
        xqc_conn_get_conn_settings_template(XQC_CONN_SETTINGS_LOW_DELAY);
    connection_settings.pacing_on = 1;
    connection_settings.ping_on = 1;
    connection_settings.init_idle_time_out = (xqc_msec_t)connection->connect_timeout_ms;
    connection_settings.idle_time_out = (xqc_msec_t)connection->idle_timeout_ms;
    connection_settings.max_streams_bidi = 8;
    connection_settings.max_streams_uni = 8;

    xqc_conn_ssl_config_t connection_ssl_config;
    memset(&connection_ssl_config, 0, sizeof(connection_ssl_config));
    connection_ssl_config.cert_verify_flag = 0;

    const xqc_cid_t *cid = xqc_connect(connection->engine, &connection_settings,
        NULL, 0, connection->host, 0, &connection_ssl_config,
        (const struct sockaddr *)&connection->peer_addr, connection->peer_addr_len,
        connection->alpn, connection);
    if (cid == NULL) {
        snprintf(error, error_size, "创建 xquic 连接失败");
        return -1;
    }
    pthread_mutex_lock(&connection->mutex);
    memcpy(&connection->cid, cid, sizeof(connection->cid));
    connection->has_cid = 1;
    pthread_mutex_unlock(&connection->mutex);
    return 0;
}

static int
xqb_drain_stream(xqb_stream_t *stream)
{
    xqb_connection_t *connection = stream->connection;
    unsigned char temporary[16384];
    int progress = 0;
    for (;;) {
        pthread_mutex_lock(&connection->mutex);
        if (stream->closed || stream->xqc_stream == NULL || stream->read_cancelled) {
            stream->needs_drain = 0;
            pthread_mutex_unlock(&connection->mutex);
            break;
        }
        size_t room = XQB_RECV_BUFFER_SIZE - stream->recv_size;
        if (room == 0) {
            stream->needs_drain = 1;
            pthread_mutex_unlock(&connection->mutex);
            break;
        }
        xqc_stream_t *xqc_stream = stream->xqc_stream;
        size_t requested = room < sizeof(temporary) ? room : sizeof(temporary);
        pthread_mutex_unlock(&connection->mutex);

        uint8_t fin = 0;
        ssize_t received = xqc_stream_recv(xqc_stream, temporary, requested, &fin);
        if (received == -XQC_EAGAIN) {
            pthread_mutex_lock(&connection->mutex);
            stream->needs_drain = 0;
            pthread_mutex_unlock(&connection->mutex);
            break;
        }
        if (received < 0) {
            char message[XQB_MAX_ERROR_LENGTH];
            snprintf(message, sizeof(message), "读取 xquic 流失败：%zd", received);
            pthread_mutex_lock(&connection->mutex);
            xqb_set_stream_error_locked(stream, message);
            pthread_mutex_unlock(&connection->mutex);
            break;
        }

        pthread_mutex_lock(&connection->mutex);
        if (received > 0) {
            size_t tail = (stream->recv_head + stream->recv_size) % XQB_RECV_BUFFER_SIZE;
            size_t first = (size_t)received;
            if (first > XQB_RECV_BUFFER_SIZE - tail) {
                first = XQB_RECV_BUFFER_SIZE - tail;
            }
            memcpy(stream->recv_buffer + tail, temporary, first);
            if ((size_t)received > first) {
                memcpy(stream->recv_buffer, temporary + first, (size_t)received - first);
            }
            stream->recv_size += (size_t)received;
            progress = 1;
        }
        if (fin) {
            stream->remote_fin = 1;
            stream->needs_drain = 0;
        }
        pthread_cond_broadcast(&stream->condition);
        pthread_mutex_unlock(&connection->mutex);
        if (fin || received == 0) {
            break;
        }
    }
    return progress;
}

static int
xqb_process_open_streams(xqb_connection_t *connection)
{
    int progress = 0;
    pthread_mutex_lock(&connection->mutex);
    xqb_stream_t *head = connection->streams;
    pthread_mutex_unlock(&connection->mutex);
    /* 流节点发布后 next 不再修改；新插入的头节点由唤醒后的下一轮处理。 */
    for (xqb_stream_t *stream = head; stream != NULL; stream = stream->next) {
        pthread_mutex_lock(&connection->mutex);
        int should_open = stream->opening && !stream->open_done && !connection->closed;
        if (should_open) {
            stream->opening = 0;
        }
        xqc_connection_t *xqc_connection = connection->xqc_connection;
        pthread_mutex_unlock(&connection->mutex);
        if (!should_open) {
            continue;
        }

        xqc_stream_t *xqc_stream = NULL;
        if (xqc_connection != NULL) {
            xqc_stream_direction_t direction = stream->bidirectional
                ? XQC_STREAM_BIDI : XQC_STREAM_UNI;
            xqc_stream = xqc_stream_create_with_direction(xqc_connection, direction, stream);
        }
        pthread_mutex_lock(&connection->mutex);
        stream->xqc_stream = xqc_stream;
        stream->open_done = 1;
        stream->open_error = xqc_stream == NULL;
        if (xqc_stream == NULL) {
            snprintf(stream->error_message, sizeof(stream->error_message), "创建 xquic 流失败");
        }
        pthread_cond_broadcast(&stream->condition);
        pthread_mutex_unlock(&connection->mutex);
        progress = 1;
    }
    return progress;
}

static int
xqb_send_stream_once(xqb_stream_t *stream)
{
    xqb_connection_t *connection = stream->connection;
    pthread_mutex_lock(&connection->mutex);
    if (stream->closed || stream->xqc_stream == NULL) {
        pthread_mutex_unlock(&connection->mutex);
        return 0;
    }
    xqb_send_chunk_t *chunk = stream->send_head;
    xqc_stream_t *xqc_stream = stream->xqc_stream;
    int send_fin = chunk == NULL && stream->local_fin_requested && !stream->local_fin_sent;
    unsigned char *data = chunk != NULL ? chunk->data + chunk->offset : NULL;
    size_t size = chunk != NULL ? chunk->size - chunk->offset : 0;
    pthread_mutex_unlock(&connection->mutex);

    if (chunk == NULL && !send_fin) {
        return 0;
    }
    ssize_t sent = xqc_stream_send(xqc_stream, data, size, send_fin ? 1 : 0);
    if (sent == -XQC_EAGAIN) {
        return 0;
    }
    if (sent < 0) {
        char message[XQB_MAX_ERROR_LENGTH];
        snprintf(message, sizeof(message), "写入 xquic 流失败：%zd", sent);
        pthread_mutex_lock(&connection->mutex);
        xqb_set_stream_error_locked(stream, message);
        pthread_mutex_unlock(&connection->mutex);
        return 0;
    }

    pthread_mutex_lock(&connection->mutex);
    if (chunk != NULL && sent > 0) {
        chunk->offset += (size_t)sent;
        stream->pending_send -= (size_t)sent;
        if (chunk->offset == chunk->size) {
            stream->send_head = chunk->next;
            if (stream->send_head == NULL) {
                stream->send_tail = NULL;
            }
            free(chunk);
        }
    } else if (send_fin) {
        stream->local_fin_sent = 1;
    }
    pthread_cond_broadcast(&stream->condition);
    pthread_mutex_unlock(&connection->mutex);
    return sent > 0 || send_fin;
}

static int
xqb_process_stream_io(xqb_connection_t *connection)
{
    int any_progress = 0;
    pthread_mutex_lock(&connection->mutex);
    xqb_stream_t *head = connection->streams;
    pthread_mutex_unlock(&connection->mutex);
    for (xqb_stream_t *stream = head; stream != NULL; stream = stream->next) {
        pthread_mutex_lock(&connection->mutex);
        int should_drain = stream->needs_drain;
        pthread_mutex_unlock(&connection->mutex);
        if (should_drain) {
            any_progress |= xqb_drain_stream(stream);
        }
    }
    for (int pass = 0; pass < 128; pass++) {
        int pass_progress = 0;
        for (xqb_stream_t *stream = head; stream != NULL; stream = stream->next) {
            pass_progress |= xqb_send_stream_once(stream);
        }
        if (!pass_progress) {
            break;
        }
        any_progress = 1;
    }
    if (any_progress && connection->engine != NULL) {
        xqc_engine_main_logic(connection->engine);
    }
    return any_progress;
}

static int
xqb_process_socket_reads(xqb_connection_t *connection)
{
    unsigned char packet[XQB_PACKET_BUFFER_SIZE];
    int processed = 0;
    for (;;) {
        struct sockaddr_storage peer;
        socklen_t peer_len = sizeof(peer);
        ssize_t received = recvfrom(connection->socket_fd, packet, sizeof(packet), 0,
            (struct sockaddr *)&peer, &peer_len);
        if (received < 0 && errno == EINTR) {
            continue;
        }
        if (received < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            break;
        }
        if (received < 0) {
            char message[XQB_MAX_ERROR_LENGTH];
            snprintf(message, sizeof(message), "QUIC UDP 接收失败：%s", strerror(errno));
            xqb_set_connection_error(connection, message);
            return -1;
        }
        connection->local_addr_len = sizeof(connection->local_addr);
        if (getsockname(connection->socket_fd, (struct sockaddr *)&connection->local_addr,
                &connection->local_addr_len) != 0) {
            xqb_set_connection_error(connection, "读取 QUIC 本地地址失败");
            return -1;
        }
        int result = xqc_engine_packet_process(connection->engine, packet, (size_t)received,
            (const struct sockaddr *)&connection->local_addr, connection->local_addr_len,
            (const struct sockaddr *)&peer, peer_len, xqb_monotonic_us(), connection);
        if (result != XQC_OK) {
            char message[XQB_MAX_ERROR_LENGTH];
            snprintf(message, sizeof(message), "处理 QUIC 数据包失败：%d", result);
            xqb_set_connection_error(connection, message);
            return -1;
        }
        processed = 1;
    }
    if (processed) {
        xqc_engine_finish_recv(connection->engine);
    }
    return 0;
}

static int
xqb_poll_timeout_ms(xqb_connection_t *connection)
{
    pthread_mutex_lock(&connection->mutex);
    uint64_t due = connection->timer_due_us;
    pthread_mutex_unlock(&connection->mutex);
    if (due == 0) {
        return 1000;
    }
    uint64_t now = xqb_monotonic_us();
    if (due <= now) {
        return 0;
    }
    uint64_t remaining_us = due - now;
    uint64_t remaining_ms = (remaining_us + 999ULL) / 1000ULL;
    return remaining_ms > 1000ULL ? 1000 : (int)remaining_ms;
}

static void *
xqb_worker_main(void *argument)
{
    xqb_connection_t *connection = (xqb_connection_t *)argument;
    xqc_engine_main_logic(connection->engine);

    for (;;) {
        pthread_mutex_lock(&connection->mutex);
        int should_stop = connection->stopping || connection->closed;
        pthread_mutex_unlock(&connection->mutex);
        if (should_stop) {
            break;
        }

        int opened = xqb_process_open_streams(connection);
        int io_progress = xqb_process_stream_io(connection);
        if (opened && !io_progress) {
            xqc_engine_main_logic(connection->engine);
        }

        struct pollfd descriptors[2];
        descriptors[0].fd = connection->socket_fd;
        descriptors[0].events = POLLIN;
        descriptors[0].revents = 0;
        descriptors[1].fd = connection->wake_pipe[0];
        descriptors[1].events = POLLIN;
        descriptors[1].revents = 0;
        int poll_result;
        do {
            poll_result = poll(descriptors, 2, xqb_poll_timeout_ms(connection));
        } while (poll_result < 0 && errno == EINTR);
        if (poll_result < 0) {
            char message[XQB_MAX_ERROR_LENGTH];
            snprintf(message, sizeof(message), "QUIC 事件轮询失败：%s", strerror(errno));
            xqb_set_connection_error(connection, message);
            break;
        }
        if ((descriptors[1].revents & POLLIN) != 0) {
            xqb_drain_wake_pipe(connection);
        }
        if ((descriptors[0].revents & POLLIN) != 0 && xqb_process_socket_reads(connection) != 0) {
            break;
        }
        if ((descriptors[0].revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
            xqb_set_connection_error(connection, "QUIC UDP 套接字异常");
            break;
        }

        uint64_t now = xqb_monotonic_us();
        pthread_mutex_lock(&connection->mutex);
        int timer_expired = connection->timer_due_us != 0 && connection->timer_due_us <= now;
        if (timer_expired) {
            connection->timer_due_us = 0;
        }
        pthread_mutex_unlock(&connection->mutex);
        if (timer_expired) {
            xqc_engine_main_logic(connection->engine);
        }
    }

    pthread_mutex_lock(&connection->mutex);
    int can_close = connection->has_cid && connection->xqc_connection != NULL;
    xqc_cid_t cid = connection->cid;
    pthread_mutex_unlock(&connection->mutex);
    if (can_close) {
        (void)xqc_conn_close(connection->engine, &cid);
        xqc_engine_main_logic(connection->engine);
    }
    xqc_engine_destroy(connection->engine);
    connection->engine = NULL;

    pthread_mutex_lock(&connection->mutex);
    connection->closed = 1;
    connection->xqc_connection = NULL;
    for (xqb_stream_t *stream = connection->streams; stream != NULL; stream = stream->next) {
        stream->closed = 1;
    }
    xqb_signal_streams_locked(connection);
    pthread_mutex_unlock(&connection->mutex);
    return NULL;
}

static xqb_connection_t *
xqb_connection_allocate(const char *host, const char *alpn, int connect_timeout_ms,
    int idle_timeout_ms)
{
    xqb_connection_t *connection = calloc(1, sizeof(*connection));
    if (connection == NULL) {
        return NULL;
    }
    connection->socket_fd = -1;
    connection->wake_pipe[0] = -1;
    connection->wake_pipe[1] = -1;
    connection->host = strdup(host);
    connection->alpn = strdup(alpn);
    connection->connect_timeout_ms = connect_timeout_ms;
    connection->idle_timeout_ms = idle_timeout_ms;
    if (connection->host == NULL || connection->alpn == NULL) {
        free(connection->host);
        free(connection->alpn);
        free(connection);
        return NULL;
    }
    if (pthread_mutex_init(&connection->mutex, NULL) != 0) {
        free(connection->host);
        free(connection->alpn);
        free(connection);
        return NULL;
    }
    if (pthread_cond_init(&connection->condition, NULL) != 0) {
        pthread_mutex_destroy(&connection->mutex);
        free(connection->host);
        free(connection->alpn);
        free(connection);
        return NULL;
    }
    if (pipe(connection->wake_pipe) != 0) {
        pthread_cond_destroy(&connection->condition);
        pthread_mutex_destroy(&connection->mutex);
        free(connection->host);
        free(connection->alpn);
        free(connection);
        return NULL;
    }
    xqb_set_nonblocking(connection->wake_pipe[0]);
    xqb_set_nonblocking(connection->wake_pipe[1]);
    return connection;
}

static void
xqb_free_send_queue(xqb_stream_t *stream)
{
    xqb_send_chunk_t *chunk = stream->send_head;
    while (chunk != NULL) {
        xqb_send_chunk_t *next = chunk->next;
        free(chunk);
        chunk = next;
    }
}

static void
xqb_connection_free(xqb_connection_t *connection)
{
    if (connection == NULL) {
        return;
    }
    if (connection->engine != NULL) {
        xqc_engine_destroy(connection->engine);
    }
    if (connection->socket_fd >= 0) {
        close(connection->socket_fd);
    }
    if (connection->wake_pipe[0] >= 0) {
        close(connection->wake_pipe[0]);
    }
    if (connection->wake_pipe[1] >= 0) {
        close(connection->wake_pipe[1]);
    }
    xqb_stream_t *stream = connection->streams;
    while (stream != NULL) {
        xqb_stream_t *next = stream->next;
        xqb_free_send_queue(stream);
        free(stream->recv_buffer);
        pthread_cond_destroy(&stream->condition);
        free(stream);
        stream = next;
    }
    pthread_cond_destroy(&connection->condition);
    pthread_mutex_destroy(&connection->mutex);
    free(connection->host);
    free(connection->alpn);
    free(connection);
}

static void
xqb_connection_stop_and_free(xqb_connection_t *connection)
{
    pthread_mutex_lock(&connection->mutex);
    connection->stopping = 1;
    xqb_signal_streams_locked(connection);
    pthread_mutex_unlock(&connection->mutex);
    xqb_wake(connection);
    if (connection->worker_started) {
        (void)pthread_join(connection->worker, NULL);
        connection->worker_started = 0;
    }
    pthread_mutex_lock(&connection->mutex);
    while (connection->active_calls > 0) {
        pthread_cond_wait(&connection->condition, &connection->mutex);
    }
    pthread_mutex_unlock(&connection->mutex);
    xqb_connection_free(connection);
}

JNIEXPORT jlong JNICALL
Java_org_dpdns_sylw_videostreamer_quic_XquicNative_nativeConnect(
    JNIEnv *env, jobject object, jstring host_string, jint port, jstring alpn_string,
    jint connect_timeout_ms, jint idle_timeout_ms)
{
    (void)object;
    if (host_string == NULL || alpn_string == NULL || port <= 0 || port > 65535
        || connect_timeout_ms <= 0 || idle_timeout_ms <= 0) {
        xqb_throw_io(env, "QUIC 连接参数无效");
        return 0;
    }
    const char *host = (*env)->GetStringUTFChars(env, host_string, NULL);
    const char *alpn = (*env)->GetStringUTFChars(env, alpn_string, NULL);
    if (host == NULL || alpn == NULL) {
        if (host != NULL) {
            (*env)->ReleaseStringUTFChars(env, host_string, host);
        }
        if (alpn != NULL) {
            (*env)->ReleaseStringUTFChars(env, alpn_string, alpn);
        }
        return 0;
    }
    if (host[0] == '\0' || alpn[0] == '\0' || strlen(alpn) > 255U) {
        (*env)->ReleaseStringUTFChars(env, host_string, host);
        (*env)->ReleaseStringUTFChars(env, alpn_string, alpn);
        xqb_throw_io(env, "QUIC 主机或 ALPN 无效");
        return 0;
    }

    xqb_connection_t *connection = xqb_connection_allocate(host, alpn,
        connect_timeout_ms, idle_timeout_ms);
    (*env)->ReleaseStringUTFChars(env, host_string, host);
    (*env)->ReleaseStringUTFChars(env, alpn_string, alpn);
    if (connection == NULL) {
        xqb_throw_io(env, "分配 QUIC 连接资源失败");
        return 0;
    }

    char error[XQB_MAX_ERROR_LENGTH];
    error[0] = '\0';
    if (xqb_resolve_and_open_socket(connection, port, error, sizeof(error)) != 0
        || xqb_initialize_engine(connection, error, sizeof(error)) != 0) {
        xqb_connection_free(connection);
        xqb_throw_io(env, error);
        return 0;
    }
    int thread_result = pthread_create(&connection->worker, NULL, xqb_worker_main, connection);
    if (thread_result != 0) {
        snprintf(error, sizeof(error), "启动 QUIC 事件线程失败：%s", strerror(thread_result));
        xqb_connection_free(connection);
        xqb_throw_io(env, error);
        return 0;
    }
    connection->worker_started = 1;

    struct timespec deadline;
    xqb_deadline_after_ms(&deadline, connect_timeout_ms);
    pthread_mutex_lock(&connection->mutex);
    int wait_result = 0;
    while (!connection->handshake_done && !connection->closed && wait_result != ETIMEDOUT) {
        wait_result = pthread_cond_timedwait(&connection->condition, &connection->mutex, &deadline);
    }
    int connected = connection->handshake_done && !connection->closed;
    if (!connected) {
        if (connection->error_message[0] != '\0') {
            snprintf(error, sizeof(error), "%s", connection->error_message);
        } else if (wait_result == ETIMEDOUT) {
            snprintf(error, sizeof(error), "QUIC 握手超时");
        } else {
            snprintf(error, sizeof(error), "QUIC 握手失败");
        }
        connection->stopping = 1;
        xqb_signal_streams_locked(connection);
    }
    pthread_mutex_unlock(&connection->mutex);
    if (!connected) {
        xqb_wake(connection);
        xqb_connection_stop_and_free(connection);
        xqb_throw_io(env, error);
        return 0;
    }

    xqb_registry_add_connection(connection);
    return connection->handle;
}

JNIEXPORT jlong JNICALL
Java_org_dpdns_sylw_videostreamer_quic_XquicNative_nativeOpenStream(
    JNIEnv *env, jobject object, jlong connection_handle, jboolean bidirectional)
{
    (void)object;
    xqb_connection_t *connection = xqb_registry_acquire_connection(connection_handle);
    if (connection == NULL) {
        xqb_throw_io(env, "QUIC 连接已关闭");
        return 0;
    }
    xqb_stream_t *stream = calloc(1, sizeof(*stream));
    if (stream == NULL) {
        xqb_registry_release_connection(connection);
        xqb_throw_io(env, "分配 QUIC 流资源失败");
        return 0;
    }
    stream->recv_buffer = malloc(XQB_RECV_BUFFER_SIZE);
    if (stream->recv_buffer == NULL || pthread_cond_init(&stream->condition, NULL) != 0) {
        free(stream->recv_buffer);
        free(stream);
        xqb_registry_release_connection(connection);
        xqb_throw_io(env, "分配 QUIC 流缓冲区失败");
        return 0;
    }
    stream->connection = connection;
    stream->bidirectional = bidirectional == JNI_TRUE;
    stream->opening = 1;

    pthread_mutex_lock(&connection->mutex);
    stream->next = connection->streams;
    connection->streams = stream;
    pthread_mutex_unlock(&connection->mutex);
    xqb_wake(connection);

    pthread_mutex_lock(&connection->mutex);
    while (!stream->open_done && !connection->closed && !connection->stopping) {
        pthread_cond_wait(&stream->condition, &connection->mutex);
    }
    int opened = stream->open_done && !stream->open_error && stream->xqc_stream != NULL;
    char error[XQB_MAX_ERROR_LENGTH];
    if (!opened) {
        snprintf(error, sizeof(error), "%s", stream->error_message[0] != '\0'
            ? stream->error_message : "创建 QUIC 流失败");
    }
    pthread_mutex_unlock(&connection->mutex);

    if (opened && !xqb_registry_add_stream(stream)) {
        opened = 0;
        snprintf(error, sizeof(error), "QUIC 连接正在关闭");
    }
    jlong result = opened ? stream->handle : 0;
    xqb_registry_release_connection(connection);
    if (!opened) {
        xqb_throw_io(env, error);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_org_dpdns_sylw_videostreamer_quic_XquicNative_nativeRead(
    JNIEnv *env, jobject object, jlong stream_handle, jbyteArray buffer, jint offset, jint length)
{
    (void)object;
    if (buffer == NULL || offset < 0 || length < 0
        || offset > (*env)->GetArrayLength(env, buffer) - length) {
        xqb_throw_io(env, "QUIC 读取参数无效");
        return -1;
    }
    xqb_stream_t *stream = xqb_registry_acquire_stream(stream_handle);
    if (stream == NULL) {
        xqb_throw_io(env, "QUIC 流已关闭");
        return -1;
    }
    xqb_connection_t *connection = stream->connection;
    pthread_mutex_lock(&connection->mutex);
    while (stream->recv_size == 0 && !stream->remote_fin && !stream->read_cancelled
        && !stream->closed && !connection->closed && !connection->stopping) {
        pthread_cond_wait(&stream->condition, &connection->mutex);
    }
    if (stream->recv_size == 0) {
        char error[XQB_MAX_ERROR_LENGTH];
        error[0] = '\0';
        if (stream->error_message[0] != '\0') {
            snprintf(error, sizeof(error), "%s", stream->error_message);
        } else if (!connection->stopping && connection->error_message[0] != '\0') {
            snprintf(error, sizeof(error), "%s", connection->error_message);
        }
        pthread_mutex_unlock(&connection->mutex);
        xqb_registry_release_connection(connection);
        if (error[0] != '\0') {
            xqb_throw_io(env, error);
        }
        return -1;
    }

    size_t count = stream->recv_size < (size_t)length ? stream->recv_size : (size_t)length;
    unsigned char *temporary = malloc(count);
    if (temporary == NULL) {
        pthread_mutex_unlock(&connection->mutex);
        xqb_registry_release_connection(connection);
        xqb_throw_io(env, "分配 QUIC 读取缓冲区失败");
        return -1;
    }
    size_t first = count;
    if (first > XQB_RECV_BUFFER_SIZE - stream->recv_head) {
        first = XQB_RECV_BUFFER_SIZE - stream->recv_head;
    }
    memcpy(temporary, stream->recv_buffer + stream->recv_head, first);
    if (count > first) {
        memcpy(temporary + first, stream->recv_buffer, count - first);
    }
    stream->recv_head = (stream->recv_head + count) % XQB_RECV_BUFFER_SIZE;
    stream->recv_size -= count;
    stream->needs_drain = 1;
    pthread_mutex_unlock(&connection->mutex);
    xqb_wake(connection);

    (*env)->SetByteArrayRegion(env, buffer, offset, (jsize)count, (const jbyte *)temporary);
    free(temporary);
    xqb_registry_release_connection(connection);
    return (*env)->ExceptionCheck(env) ? -1 : (jint)count;
}

JNIEXPORT void JNICALL
Java_org_dpdns_sylw_videostreamer_quic_XquicNative_nativeWrite(
    JNIEnv *env, jobject object, jlong stream_handle, jbyteArray buffer, jint offset, jint length)
{
    (void)object;
    if (buffer == NULL || offset < 0 || length < 0
        || offset > (*env)->GetArrayLength(env, buffer) - length) {
        xqb_throw_io(env, "QUIC 写入参数无效");
        return;
    }
    if (length == 0) {
        return;
    }
    xqb_stream_t *stream = xqb_registry_acquire_stream(stream_handle);
    if (stream == NULL) {
        xqb_throw_io(env, "QUIC 流已关闭");
        return;
    }
    xqb_connection_t *connection = stream->connection;
    xqb_send_chunk_t *chunk = malloc(sizeof(*chunk) + (size_t)length);
    if (chunk == NULL) {
        xqb_registry_release_connection(connection);
        xqb_throw_io(env, "分配 QUIC 发送缓冲区失败");
        return;
    }
    chunk->next = NULL;
    chunk->size = (size_t)length;
    chunk->offset = 0;
    (*env)->GetByteArrayRegion(env, buffer, offset, length, (jbyte *)chunk->data);
    if ((*env)->ExceptionCheck(env)) {
        free(chunk);
        xqb_registry_release_connection(connection);
        return;
    }

    pthread_mutex_lock(&connection->mutex);
    while (stream->pending_send > 0
        && stream->pending_send + (size_t)length > XQB_MAX_PENDING_SEND
        && !stream->closed && !connection->closed && !connection->stopping) {
        pthread_cond_wait(&stream->condition, &connection->mutex);
    }
    if (stream->closed || stream->local_fin_requested || connection->closed || connection->stopping) {
        char error[XQB_MAX_ERROR_LENGTH];
        snprintf(error, sizeof(error), "%s", stream->error_message[0] != '\0'
            ? stream->error_message
            : (connection->error_message[0] != '\0' ? connection->error_message : "QUIC 流已关闭"));
        pthread_mutex_unlock(&connection->mutex);
        free(chunk);
        xqb_registry_release_connection(connection);
        xqb_throw_io(env, error);
        return;
    }
    if (stream->send_tail == NULL) {
        stream->send_head = chunk;
    } else {
        stream->send_tail->next = chunk;
    }
    stream->send_tail = chunk;
    stream->pending_send += (size_t)length;
    pthread_mutex_unlock(&connection->mutex);
    xqb_wake(connection);
    xqb_registry_release_connection(connection);
}

JNIEXPORT void JNICALL
Java_org_dpdns_sylw_videostreamer_quic_XquicNative_nativeFlush(
    JNIEnv *env, jobject object, jlong stream_handle)
{
    (void)object;
    xqb_stream_t *stream = xqb_registry_acquire_stream(stream_handle);
    if (stream == NULL) {
        xqb_throw_io(env, "QUIC 流已关闭");
        return;
    }
    xqb_connection_t *connection = stream->connection;
    xqb_wake(connection);
    pthread_mutex_lock(&connection->mutex);
    while (stream->pending_send > 0 && !stream->closed
        && !connection->closed && !connection->stopping) {
        pthread_cond_wait(&stream->condition, &connection->mutex);
    }
    char error[XQB_MAX_ERROR_LENGTH];
    error[0] = '\0';
    if (stream->pending_send > 0 || stream->closed || connection->closed || connection->stopping) {
        snprintf(error, sizeof(error), "%s", stream->error_message[0] != '\0'
            ? stream->error_message
            : (connection->error_message[0] != '\0' ? connection->error_message : "QUIC 流已关闭"));
    }
    pthread_mutex_unlock(&connection->mutex);
    xqb_registry_release_connection(connection);
    if (error[0] != '\0') {
        xqb_throw_io(env, error);
    }
}

JNIEXPORT void JNICALL
Java_org_dpdns_sylw_videostreamer_quic_XquicNative_nativeFinishStream(
    JNIEnv *env, jobject object, jlong stream_handle)
{
    (void)object;
    xqb_stream_t *stream = xqb_registry_acquire_stream(stream_handle);
    if (stream == NULL) {
        return;
    }
    xqb_connection_t *connection = stream->connection;
    pthread_mutex_lock(&connection->mutex);
    stream->local_fin_requested = 1;
    pthread_mutex_unlock(&connection->mutex);
    xqb_wake(connection);
    pthread_mutex_lock(&connection->mutex);
    while (!stream->local_fin_sent && !stream->closed
        && !connection->closed && !connection->stopping) {
        pthread_cond_wait(&stream->condition, &connection->mutex);
    }
    char error[XQB_MAX_ERROR_LENGTH];
    error[0] = '\0';
    if (!stream->local_fin_sent && !connection->stopping) {
        snprintf(error, sizeof(error), "%s", stream->error_message[0] != '\0'
            ? stream->error_message
            : (connection->error_message[0] != '\0' ? connection->error_message : "关闭 QUIC 流失败"));
    }
    pthread_mutex_unlock(&connection->mutex);
    xqb_registry_release_connection(connection);
    if (error[0] != '\0') {
        xqb_throw_io(env, error);
    }
}

JNIEXPORT void JNICALL
Java_org_dpdns_sylw_videostreamer_quic_XquicNative_nativeCancelRead(
    JNIEnv *env, jobject object, jlong stream_handle)
{
    (void)env;
    (void)object;
    xqb_stream_t *stream = xqb_registry_acquire_stream(stream_handle);
    if (stream == NULL) {
        return;
    }
    xqb_connection_t *connection = stream->connection;
    pthread_mutex_lock(&connection->mutex);
    stream->read_cancelled = 1;
    pthread_cond_broadcast(&stream->condition);
    pthread_mutex_unlock(&connection->mutex);
    xqb_registry_release_connection(connection);
}

JNIEXPORT void JNICALL
Java_org_dpdns_sylw_videostreamer_quic_XquicNative_nativeCloseConnection(
    JNIEnv *env, jobject object, jlong connection_handle)
{
    (void)env;
    (void)object;
    xqb_connection_t *connection = xqb_registry_take_connection(connection_handle);
    if (connection != NULL) {
        xqb_connection_stop_and_free(connection);
    }
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *virtual_machine, void *reserved)
{
    (void)virtual_machine;
    (void)reserved;
    return JNI_VERSION_1_6;
}
