
MAKE = make
CUR_DIR   := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
BROM_DIR  := $(CUR_DIR)/../..
BOOST_DIR := /home/xzh/Downloads/boost_1_80_0
CXX = riscv64-unknown-linux-gnu-g++
CXXFLAGS = --std=c++11 -static -fvisibility=hidden -s -O3 -I$(BROM_DIR)/include -I$(BOOST_DIR) -I.

TARGETS = \
	run/test-evict-ratio \
	run/test-evict \
	run/test-evict-combine \

OBJECTS = \
	cache/list.o \
	cache/cache.o \
	cache/algorithm.o \
	common/definitions.o \
	util/random.o \
	util/statistics.o \

HEADERS = $(wildcard cache/*.hpp) $(wildcard database/*.hpp) $(wildcard util/*.hpp)

all: $(TARGETS)

$(OBJECTS): %.o:%.cpp $(HEADERS)
	$(CXX) $(CXXFLAGS) -c $< -o $@

$(TARGETS): run/% : test/%.cpp $(OBJECTS)
	$(CXX) $(CXXFLAGS) $^ -lpthread -o $@

clean:
	-rm $(TARGETS) $(OBJECTS)
