# Minimal Boost package for this build: aMule uses Boost headers and
# BOOST_ERROR_CODE_HEADER_ONLY, but does not link compiled Boost libraries.
set(Boost_FOUND TRUE)
set(Boost_VERSION 1.83.0)
set(Boost_VERSION_STRING "${Boost_VERSION}")
set(Boost_VERSION_MAJOR 1)
set(Boost_VERSION_MINOR 83)
set(Boost_VERSION_PATCH 0)
set(Boost_VERSION_MACRO 108300)
set(Boost_INCLUDE_DIRS "${CMAKE_CURRENT_LIST_DIR}/../../../include")
set(Boost_LIBRARIES "")

if(NOT TARGET Boost::headers)
	add_library(Boost::headers INTERFACE IMPORTED)
	set_property(TARGET Boost::headers PROPERTY INTERFACE_INCLUDE_DIRECTORIES "${Boost_INCLUDE_DIRS}")
endif()

if(NOT TARGET Boost::boost)
	add_library(Boost::boost INTERFACE IMPORTED)
	set_property(TARGET Boost::boost PROPERTY INTERFACE_LINK_LIBRARIES Boost::headers)
endif()
