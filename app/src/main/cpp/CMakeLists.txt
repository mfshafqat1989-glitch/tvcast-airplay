cmake_minimum_required(VERSION 3.22.1)
project(tvcast_airplay C CXX)

set(UX ${CMAKE_CURRENT_SOURCE_DIR}/uxplay/lib)
set(PL ${CMAKE_CURRENT_SOURCE_DIR}/libplist)
set(AL ${CMAKE_CURRENT_SOURCE_DIR}/alac)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_options(-O2 -Wno-unused-parameter -Wno-unused-variable -Wno-unused-function)

# ---------------------------------------------------------------- OpenSSL (libcrypto)
add_library(crypto INTERFACE)
if(ANDROID)
    set(OSSL ${OPENSSL_ROOT}/${ANDROID_ABI})
    if(NOT EXISTS ${OSSL}/lib/libcrypto.a)
        message(FATAL_ERROR "libcrypto.a not found in ${OSSL}/lib — run scripts/build-openssl.sh first")
    endif()
    add_library(crypto_static STATIC IMPORTED)
    set_target_properties(crypto_static PROPERTIES IMPORTED_LOCATION ${OSSL}/lib/libcrypto.a)
    target_link_libraries(crypto INTERFACE crypto_static)
    target_include_directories(crypto INTERFACE ${OSSL}/include)
else()
    find_package(OpenSSL REQUIRED)
    target_link_libraries(crypto INTERFACE OpenSSL::Crypto)
endif()

# ---------------------------------------------------------------- libplist
file(GLOB PLIST_SRC ${PL}/src/*.c ${PL}/libcnary/*.c)
add_library(plist STATIC ${PLIST_SRC})
target_include_directories(plist PUBLIC ${PL}/include PRIVATE ${PL}/src ${PL}/libcnary/include)
target_compile_definitions(plist PRIVATE
    HAVE_MEMMEM HAVE_STRNDUP HAVE_GMTIME_R HAVE_LOCALTIME_R HAVE_STRPTIME
    HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE PACKAGE_STRING="libplist" PACKAGE_VERSION="2.7.0")

# ---------------------------------------------------------------- UxPlay AirPlay engine
file(GLOB UX_SRC
    ${UX}/*.c ${UX}/llhttp/*.c ${UX}/playfair/*.c ${UX}/fairplay_v2/*.c ${UX}/mdnsd/*.c)
add_library(uxplay STATIC ${UX_SRC})
target_include_directories(uxplay
    PUBLIC ${UX}
    PRIVATE ${UX}/llhttp ${UX}/playfair ${UX}/fairplay_v2 ${UX}/mdnsd)
target_compile_definitions(uxplay PRIVATE
    STANDALONE __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS TARGET_POSIX _LINUX _REENTRANT
    NOHOLD PLIST_210 PLIST_230 OPENSSL_API_COMPAT=0x10101000L)
target_link_libraries(uxplay PUBLIC plist crypto)
if(NOT ANDROID)
    target_link_libraries(uxplay PUBLIC pthread)
endif()

# ---------------------------------------------------------------- Apple ALAC decoder
add_library(alac STATIC
    ${AL}/ALACDecoder.cpp ${AL}/ALACBitUtilities.c ${AL}/EndianPortable.c
    ${AL}/ag_dec.c ${AL}/dp_dec.c ${AL}/matrix_dec.c)
target_include_directories(alac PUBLIC ${AL})

# ---------------------------------------------------------------- JNI bridge
add_library(airplay SHARED airplay_jni.c alac_wrapper.cpp)
target_include_directories(airplay PRIVATE ${UX})
target_link_libraries(airplay uxplay alac)
if(ANDROID)
    target_link_libraries(airplay log)
endif()
