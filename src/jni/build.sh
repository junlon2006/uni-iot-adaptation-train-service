#!/bin/bash
g++ -Werror -fPIC -std=c++11 -shared -o libWrapperEngine.so log.cc LocalAsrEngine.c -lengine_c -L. -lstdc++
