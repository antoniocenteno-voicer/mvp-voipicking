cmake_minimum_required(VERSION 3.22.1)
project(voxpicking_native)

set(CMAKE_CXX_STANDARD 17)

set(WHISPER_CPP_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)

if(EXISTS ${WHISPER_CPP_DIR}/CMakeLists.txt)
    # Submódulo presente: builda whisper.cpp de verdade e liga ao JNI.
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    add_subdirectory(${WHISPER_CPP_DIR} whisper_build)

    add_library(whisper_jni SHARED whisper_jni.cpp)
    target_include_directories(whisper_jni PRIVATE ${WHISPER_CPP_DIR}/include ${WHISPER_CPP_DIR}/ggml/include)
    target_link_libraries(whisper_jni whisper log android)
    target_compile_definitions(whisper_jni PRIVATE VOX_WHISPER_DISPONIVEL=1)
else()
    # Submódulo ainda não clonado: gera stub .so pra não quebrar o build do MVP.
    message(WARNING "whisper.cpp não encontrado em ${WHISPER_CPP_DIR} — buildando whisper_jni em modo stub. Veja README.md.")
    add_library(whisper_jni SHARED whisper_jni.cpp)
    target_link_libraries(whisper_jni log android)
endif()
