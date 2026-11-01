cmake_minimum_required(VERSION 3.13)
project(expodatagrid)

set(CMAKE_CXX_STANDARD 17)
add_library(expodatagrid SHARED src/main/cpp/native_buffers.cpp)
target_compile_options(expodatagrid PRIVATE -O2 -Wall -Werror)
