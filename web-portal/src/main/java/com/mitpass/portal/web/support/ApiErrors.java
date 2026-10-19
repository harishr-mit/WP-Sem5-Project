package com.mitpass.portal.web.support;

import com.mitpass.portal.client.ApiModels.FieldError;
import com.mitpass.portal.client.ApiProblemException;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.validation.BindingResult;

/**
 * Puts a service's errors into the form's {@link BindingResult} (docs/plans/phase-6.md §4), so they look like the
 * portal's own validation errors: a 400's field errors go onto the fields with the same name ({@code title},
 * {@code sessions[1].startsAt} …), everything else (409, 422, unknown fields) becomes a form-level error.
 */
public final class ApiErrors {

    private ApiErrors() {}

    public static void copyInto(ApiProblemException ex, BindingResult binding) {
        if (ex.getErrors().isEmpty()) {
            binding.reject("api", ex.getMessage());
            return;
        }
        BeanWrapperImpl form = new BeanWrapperImpl(binding.getTarget());
        for (FieldError error : ex.getErrors()) {
            if (error.field() != null && readable(form, error.field())) {
                binding.rejectValue(error.field(), "api", error.message());
            } else {
                binding.reject("api", error.message());
            }
        }
    }

    private static boolean readable(BeanWrapperImpl form, String field) {
        try {
            return form.isReadableProperty(field);
        } catch (RuntimeException outOfRange) {
            return false; // e.g. sessions[5] when the form has 3 rows
        }
    }
}
