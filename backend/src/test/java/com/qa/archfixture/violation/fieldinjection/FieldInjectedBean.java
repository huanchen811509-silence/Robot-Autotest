package com.qa.archfixture.violation.fieldinjection;

import org.springframework.beans.factory.annotation.Autowired;

/** 违规 fixture：字段注入（R6 必须拦住）。 */
public class FieldInjectedBean {

    @Autowired
    private Object dependency;

    public Object dependency() {
        return dependency;
    }
}
