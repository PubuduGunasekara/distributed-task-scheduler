package com.taskscheduler.api.mapper;

import com.taskscheduler.api.dto.TaskResponse;
import com.taskscheduler.domain.model.Task;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * MapStruct mapper — generates implementation at compile time.
 *
 * Because we set -Amapstruct.defaultComponentModel=spring in pom.xml,
 * the generated TaskMapperImpl is automatically a Spring bean.
 * No @Component annotation needed here.
 *
 * MapStruct matches fields by name. Task.getId() → TaskResponse.id(),
 * Task.getName() → TaskResponse.name(), etc. All fields match, so
 * no custom @Mapping annotations are required here.
 *
 * Interview note: MapStruct generates code at compile time (zero
 * reflection at runtime), making it significantly faster than
 * ModelMapper or Dozer which use reflection.
 */
@Mapper
public interface TaskMapper {

    TaskResponse toResponse(Task task);

    List<TaskResponse> toResponseList(List<Task> tasks);
}