package com.mitpass.event.mapper;

import com.mitpass.event.domain.Category;
import com.mitpass.event.web.dto.CategoryResponse;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper
public interface CategoryMapper {

    CategoryResponse toResponse(Category category);

    List<CategoryResponse> toResponses(List<Category> categories);
}
