cmake_minimum_required(VERSION 3.22)
project(drishti_scene_vlm CXX)

# llama.cpp is pinned, not vendored. The exact revision is recorded in
# docs/SCENE_MODE_VLM.md; scripts/bootstrap_llama.sh clones it. It stays out of
# the repository because the tree is large and the repository is public, and
# because pinning a revision we can re-fetch is stronger provenance than a
# snapshot someone may have edited in place.
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../third_party/llama.cpp")
cmake_path(NORMAL_PATH LLAMA_DIR)
set(KLEIDIAI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../third_party/kleidiai-v1.24.0")
cmake_path(NORMAL_PATH KLEIDIAI_DIR)

if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
  message(FATAL_ERROR
    "llama.cpp not found at ${LLAMA_DIR}.\n"
    "Run scripts/bootstrap_llama.sh first — it clones the pinned revision "
    "recorded in docs/SCENE_MODE_VLM.md.")
endif()

# llama.cpp otherwise downloads KleidiAI while CMake is configuring. Prefer a
# verified local release tree when one has been staged so the Android build is
# not coupled to a nested network request.
if(EXISTS "${KLEIDIAI_DIR}/CMakeLists.txt")
  set(FETCHCONTENT_SOURCE_DIR_KLEIDIAI "${KLEIDIAI_DIR}" CACHE PATH "" FORCE)
endif()

# Build flags measured on device, not inherited. See docs/SCENE_MODE_VLM.md §4.1
# and §4.3: GGML_LLAMAFILE is worth 14.7x on prompt processing, which is where
# every image token is spent, and KleidiAI is worth a further 2.1x. Turning
# either off looks harmless and is not.
set(GGML_NATIVE        OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI  ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP        OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE     ON  CACHE BOOL "" FORCE)
set(LLAMA_CURL         OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS  OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS  ON  CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

# LLAMA_BUILD_TOOLS=OFF skips tools/, so mtmd is added directly. It is the
# multimodal half and the whole reason this module exists.
add_subdirectory("${LLAMA_DIR}/tools/mtmd" "${CMAKE_CURRENT_BINARY_DIR}/mtmd" EXCLUDE_FROM_ALL)

add_library(drishti_scene_vlm SHARED scene_vlm.cpp)

target_include_directories(drishti_scene_vlm PRIVATE
  "${LLAMA_DIR}/include"
  "${LLAMA_DIR}/ggml/include"
  "${LLAMA_DIR}/tools/mtmd")

target_link_libraries(drishti_scene_vlm PRIVATE llama mtmd android log)
