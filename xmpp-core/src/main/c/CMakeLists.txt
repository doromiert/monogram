cmake_minimum_required(VERSION 3.16)
project(monogram_omemo2 LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LIBOMEMO_C_SOURCE "$ENV{LIBOMEMO_C_SOURCE}" CACHE PATH "Nix-pinned libomemo-c source")
set(PROTOBUF_C_SOURCE "$ENV{PROTOBUF_C_SOURCE}" CACHE PATH "Nix-pinned protobuf-c source")
if(NOT EXISTS "${LIBOMEMO_C_SOURCE}/src/protocol.c" OR NOT EXISTS "${PROTOBUF_C_SOURCE}/protobuf-c/protobuf-c.c")
    message(FATAL_ERROR "Build inside nix develop: pinned OMEMO/protobuf sources are required")
endif()
find_program(PROTOC_C protoc-c REQUIRED)
set(generated "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${generated}")
set(proto_sources)
foreach(name LocalStorageProtocol WhisperTextProtocol OMEMO)
    list(APPEND proto_sources "${generated}/${name}.pb-c.c")
    add_custom_command(OUTPUT "${generated}/${name}.pb-c.c" "${generated}/${name}.pb-c.h"
        COMMAND "${PROTOC_C}" "--proto_path=${LIBOMEMO_C_SOURCE}/protobuf" "--c_out=${generated}" "${LIBOMEMO_C_SOURCE}/protobuf/${name}.proto"
        DEPENDS "${LIBOMEMO_C_SOURCE}/protobuf/${name}.proto" VERBATIM)
endforeach()

add_library(monogram_protobuf_c STATIC "${PROTOBUF_C_SOURCE}/protobuf-c/protobuf-c.c")
target_include_directories(monogram_protobuf_c PUBLIC "${PROTOBUF_C_SOURCE}")
# The upstream CMake entry point expects generated files in its read-only source tree.
add_subdirectory("${LIBOMEMO_C_SOURCE}/src/curve25519" "${CMAKE_CURRENT_BINARY_DIR}/curve25519")
file(GLOB ratchet_sources CONFIGURE_DEPENDS "${LIBOMEMO_C_SOURCE}/src/*.c")
list(FILTER ratchet_sources EXCLUDE REGEX "\\.pb-c\\.c$")
add_library(monogram_ratchet STATIC ${ratchet_sources} ${proto_sources} $<TARGET_OBJECTS:curve25519>)
target_include_directories(monogram_ratchet PUBLIC "${LIBOMEMO_C_SOURCE}/src" "${generated}")
target_include_directories(monogram_ratchet PRIVATE "${LIBOMEMO_C_SOURCE}/src/curve25519"
    "${LIBOMEMO_C_SOURCE}/src/curve25519/ed25519/nacl_includes"
    "${LIBOMEMO_C_SOURCE}/src/curve25519/ed25519/additions"
    "${LIBOMEMO_C_SOURCE}/src/curve25519/ed25519/sha512"
    "${LIBOMEMO_C_SOURCE}/src/curve25519/ed25519")
target_link_libraries(monogram_ratchet PUBLIC monogram_protobuf_c)

add_library(monogram_omemo2 SHARED omemo2_jni.cpp crypto_provider.c)
target_link_libraries(monogram_omemo2 PRIVATE monogram_ratchet)
target_compile_options(monogram_omemo2 PRIVATE -Wall -Wextra -Wno-unused-parameter)
set_target_properties(monogram_omemo2 monogram_ratchet monogram_protobuf_c curve25519 PROPERTIES
    C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
target_link_options(monogram_omemo2 PRIVATE "-Wl,--exclude-libs,ALL")
target_link_options(monogram_omemo2 PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_LIST_DIR}/exports.map")
set_property(TARGET monogram_omemo2 APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_LIST_DIR}/exports.map")
if(ANDROID)
    if(NOT TARGET crypto)
        add_library(crypto STATIC IMPORTED)
        set_target_properties(crypto PROPERTIES IMPORTED_LOCATION
            "${CMAKE_CURRENT_LIST_DIR}/../../../../TMessagesProj/jni/prebuild/lib/${ANDROID_ABI}/libcrypto.a")
    endif()
    target_include_directories(monogram_omemo2 PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../../TMessagesProj/jni/third_party/boringssl/include")
    target_link_libraries(monogram_omemo2 PRIVATE crypto)
    target_link_options(monogram_omemo2 PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
else()
    find_package(JNI REQUIRED)
    find_package(OpenSSL REQUIRED)
    target_include_directories(monogram_omemo2 PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(monogram_omemo2 PRIVATE OpenSSL::Crypto)
endif()
