cmake_minimum_required(VERSION 2.8.3)
project(knowrob_entity)

find_package(catkin REQUIRED COMPONENTS knowrob_common knowrob_memory)

catkin_package(
    CATKIN_DEPENDS knowrob_common knowrob_memory
)
catkin_add_plunit(knowrob/entity)
  
install(DIRECTORY prolog/
  DESTINATION ${CATKIN_PACKAGE_SHARE_DESTINATION}/prolog)
