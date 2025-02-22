/*
 * Copyright (C) 2020 Graylog, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Server Side Public License, version 1,
 * as published by MongoDB, Inc.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Server Side Public License for more details.
 *
 * You should have received a copy of the Server Side Public License
 * along with this program. If not, see
 * <http://www.mongodb.com/licensing/server-side-public-license>.
 */
package org.graylog.plugins.views.search.validation;

 import org.apache.lucene.queryparser.classic.QueryParserConstants;
import org.apache.lucene.queryparser.classic.Token;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class FieldTypeValidationTest {

    private FieldTypeValidation fieldTypeValidation;

    @BeforeEach
    void setUp() {
        fieldTypeValidation = new FieldTypeValidationImpl();
    }

    @Test
    void validateFieldValueType() {
        assertThat(fieldTypeValidation.validateFieldValueType(term("123"), "long")).isNotPresent();
        assertThat(fieldTypeValidation.validateFieldValueType(term("ABC"), "long")).isPresent();
        assertThat(fieldTypeValidation.validateFieldValueType(term("2020-07-29T12:00:00.000-05:00"), "date")).isNotPresent();
    }

    private ParsedTerm term(String value) {
        final Token token = Token.newToken(QueryParserConstants.TERM, "foo");
        token.beginLine = 1;
        token.beginColumn = 0;
        token.endLine = 1;
        token.endColumn = 3;
        return ParsedTerm.builder()
                .field("foo")
                .valueToken(ImmutableToken.create(token))
                .value(value)
                .build();
    }
}
