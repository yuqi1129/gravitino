/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.requests;

import com.datastrato.gravitino.rest.RESTRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import org.apache.commons.lang3.StringUtils;

/** Represents a request to add a user. */
@Getter
@EqualsAndHashCode
@ToString
@Builder
@Jacksonized
public class UserAddRequest implements RESTRequest {

  @JsonProperty("name")
  private final String name;

  /** Default constructor for UserAddRequest. (Used for Jackson deserialization.) */
  public UserAddRequest() {
    this(null);
  }

  /**
   * Creates a new UserAddRequest.
   *
   * @param name The name of the user.
   */
  public UserAddRequest(String name) {
    super();
    this.name = name;
  }

  /**
   * Validates the {@link UserAddRequest} request.
   *
   * @throws IllegalArgumentException If the request is invalid, this exception is thrown.
   */
  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(name), "\"name\" field is required and cannot be empty");
  }
}
