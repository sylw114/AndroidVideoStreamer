cmake_minimum_required(VERSION 3.22.1)

project(xquicbridge LANGUAGES C)

get_filename_component(REPOSITORY_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(XQUIC_ROOT "${REPOSITORY_ROOT}/third_party/xquic")
set(TONGSUO_ROOT "${REPOSITORY_ROOT}/third_party/build/tongsuo/${ANDROID_ABI}")
set(TONGSUO_INCLUDE "${TONGSUO_ROOT}/include")
set(TONGSUO_SSL "${TONGSUO_ROOT}/lib/libssl.a")
set(TONGSUO_CRYPTO "${TONGSUO_ROOT}/lib/libcrypto.a")

if(NOT EXISTS "${XQUIC_ROOT}/include/xquic/xquic.h")
    message(FATAL_ERROR "缺少 xquic 子模块，请执行 git submodule update --init --recursive")
endif()

if(NOT EXISTS "${TONGSUO_SSL}" OR NOT EXISTS "${TONGSUO_CRYPTO}")
    message(FATAL_ERROR
        "缺少 ${ANDROID_ABI} 的 Tongsuo 静态库。请先执行 bash scripts/build-tongsuo-android.sh")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# xquic 官方 CMake 同时生成静态库和共享库；Android 只需要前者。这里固定使用
# v1.9.5 的官方源文件集合，既避免 APK 内重复打包 libxquic.so，也避免共享目标误链
# Android 不存在的独立 libpthread。TLS 实现只编入 BabaSSL/Tongsuo 版本。
set(XQUIC_SOURCES
    ${XQUIC_ROOT}/src/http3/xqc_h3_conn.c
    ${XQUIC_ROOT}/src/http3/xqc_h3_stream.c
    ${XQUIC_ROOT}/src/http3/xqc_h3_request.c
    ${XQUIC_ROOT}/src/http3/frame/xqc_h3_frame.c
    ${XQUIC_ROOT}/src/http3/xqc_h3_header.c
    ${XQUIC_ROOT}/src/http3/xqc_h3_ctx.c
    ${XQUIC_ROOT}/src/http3/xqc_h3_defs.c
    ${XQUIC_ROOT}/src/http3/qpack/xqc_qpack.c
    ${XQUIC_ROOT}/src/http3/qpack/xqc_prefixed_int.c
    ${XQUIC_ROOT}/src/http3/qpack/xqc_prefixed_str.c
    ${XQUIC_ROOT}/src/http3/qpack/xqc_decoder.c
    ${XQUIC_ROOT}/src/http3/qpack/xqc_encoder.c
    ${XQUIC_ROOT}/src/http3/qpack/xqc_rep.c
    ${XQUIC_ROOT}/src/http3/qpack/xqc_ins.c
    ${XQUIC_ROOT}/src/http3/qpack/stable/xqc_stable.c
    ${XQUIC_ROOT}/src/http3/qpack/dtable/xqc_dtable.c
    ${XQUIC_ROOT}/src/http3/xqc_h3_ext_dgram.c
    ${XQUIC_ROOT}/src/http3/xqc_h3_ext_bytestream.c
    ${XQUIC_ROOT}/src/transport/xqc_engine.c
    ${XQUIC_ROOT}/src/transport/xqc_conn.c
    ${XQUIC_ROOT}/src/transport/xqc_client.c
    ${XQUIC_ROOT}/src/transport/xqc_cid.c
    ${XQUIC_ROOT}/src/transport/xqc_packet_parser.c
    ${XQUIC_ROOT}/src/transport/xqc_frame_parser.c
    ${XQUIC_ROOT}/src/transport/xqc_stream.c
    ${XQUIC_ROOT}/src/transport/xqc_datagram.c
    ${XQUIC_ROOT}/src/transport/xqc_packet_out.c
    ${XQUIC_ROOT}/src/transport/xqc_packet_in.c
    ${XQUIC_ROOT}/src/transport/xqc_send_ctl.c
    ${XQUIC_ROOT}/src/transport/xqc_send_queue.c
    ${XQUIC_ROOT}/src/transport/xqc_packet.c
    ${XQUIC_ROOT}/src/transport/xqc_frame.c
    ${XQUIC_ROOT}/src/transport/xqc_recv_record.c
    ${XQUIC_ROOT}/src/transport/xqc_pacing.c
    ${XQUIC_ROOT}/src/transport/xqc_utils.c
    ${XQUIC_ROOT}/src/transport/xqc_multipath.c
    ${XQUIC_ROOT}/src/transport/xqc_defs.c
    ${XQUIC_ROOT}/src/transport/xqc_transport_params.c
    ${XQUIC_ROOT}/src/transport/xqc_quic_lb.c
    ${XQUIC_ROOT}/src/transport/xqc_timer.c
    ${XQUIC_ROOT}/src/transport/xqc_reinjection.c
    ${XQUIC_ROOT}/src/transport/xqc_recv_timestamps_info.c
    ${XQUIC_ROOT}/src/transport/reinjection_control/xqc_reinj_default.c
    ${XQUIC_ROOT}/src/transport/reinjection_control/xqc_reinj_deadline.c
    ${XQUIC_ROOT}/src/transport/reinjection_control/xqc_reinj_dgram.c
    ${XQUIC_ROOT}/src/transport/scheduler/xqc_scheduler_minrtt.c
    ${XQUIC_ROOT}/src/transport/scheduler/xqc_scheduler_common.c
    ${XQUIC_ROOT}/src/transport/scheduler/xqc_scheduler_backup.c
    ${XQUIC_ROOT}/src/transport/scheduler/xqc_scheduler_backup_fec.c
    ${XQUIC_ROOT}/src/transport/scheduler/xqc_scheduler_rap.c
    ${XQUIC_ROOT}/src/tls/xqc_hkdf.c
    ${XQUIC_ROOT}/src/tls/xqc_null_crypto.c
    ${XQUIC_ROOT}/src/tls/xqc_crypto.c
    ${XQUIC_ROOT}/src/tls/xqc_tls_ctx.c
    ${XQUIC_ROOT}/src/tls/xqc_tls.c
    ${XQUIC_ROOT}/src/tls/babassl/xqc_hkdf_impl.c
    ${XQUIC_ROOT}/src/tls/babassl/xqc_crypto_impl.c
    ${XQUIC_ROOT}/src/tls/babassl/xqc_ssl_if_impl.c
    ${XQUIC_ROOT}/src/common/xqc_random.c
    ${XQUIC_ROOT}/src/common/xqc_str.c
    ${XQUIC_ROOT}/src/common/xqc_log.c
    ${XQUIC_ROOT}/src/common/xqc_log_event_callback.c
    ${XQUIC_ROOT}/src/common/xqc_time.c
    ${XQUIC_ROOT}/src/common/utils/huffman/xqc_huffman_code.c
    ${XQUIC_ROOT}/src/common/utils/huffman/xqc_huffman.c
    ${XQUIC_ROOT}/src/common/utils/vint/xqc_discrete_int_parser.c
    ${XQUIC_ROOT}/src/common/utils/vint/xqc_variable_len_int.c
    ${XQUIC_ROOT}/src/common/utils/ringarray/xqc_ring_array.c
    ${XQUIC_ROOT}/src/common/utils/ringmem/xqc_ring_mem.c
    ${XQUIC_ROOT}/src/common/utils/2d_hash/xqc_2d_hash_table.c
    ${XQUIC_ROOT}/src/common/utils/var_buf/xqc_var_buf.c
    ${XQUIC_ROOT}/src/congestion_control/xqc_cubic.c
    ${XQUIC_ROOT}/src/congestion_control/xqc_bbr.c
    ${XQUIC_ROOT}/src/congestion_control/xqc_window_filter.c
    ${XQUIC_ROOT}/src/congestion_control/xqc_sample.c
)

add_library(xquic-static STATIC ${XQUIC_SOURCES})
target_compile_definitions(xquic-static PRIVATE
    NDEBUG_PRINT
    NPRINT_MALLOC
    XQC_ONLY_ERROR_LOG
)
target_compile_options(xquic-static PRIVATE
    -Wall
    -Werror
    -Wno-unused
    -Wno-pointer-sign
    -Wno-format-security
)
target_include_directories(xquic-static PRIVATE
    ${XQUIC_ROOT}/include
    ${XQUIC_ROOT}
    ${TONGSUO_INCLUDE}
)

add_library(tongsuo_ssl STATIC IMPORTED GLOBAL)
set_target_properties(tongsuo_ssl PROPERTIES IMPORTED_LOCATION "${TONGSUO_SSL}")
add_library(tongsuo_crypto STATIC IMPORTED GLOBAL)
set_target_properties(tongsuo_crypto PROPERTIES IMPORTED_LOCATION "${TONGSUO_CRYPTO}")

add_library(xquicbridge SHARED xquic_jni.c)
target_compile_definitions(xquicbridge PRIVATE _GNU_SOURCE)
target_compile_options(xquicbridge PRIVATE -Wall -Wextra -Werror)
target_include_directories(xquicbridge PRIVATE
    ${XQUIC_ROOT}/include
    ${TONGSUO_INCLUDE}
)
target_link_options(xquicbridge PRIVATE "-Wl,--exclude-libs,ALL")
target_link_libraries(xquicbridge PRIVATE
    xquic-static
    tongsuo_ssl
    tongsuo_crypto
    android
    log
    dl
    m
)
