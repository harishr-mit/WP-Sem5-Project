package com.mitpass.event.service;

import com.mitpass.event.mapper.CategoryMapper;
import com.mitpass.event.repository.CategoryRepository;
import com.mitpass.event.web.dto.CategoryResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categories;
    private final CategoryMapper mapper;

    public CategoryService(CategoryRepository categories, CategoryMapper mapper) {
        this.categories = categories;
        this.mapper = mapper;
    }

    public List<CategoryResponse> list() {
        return mapper.toResponses(categories.findAllByOrderByNameAsc());
    }
}
