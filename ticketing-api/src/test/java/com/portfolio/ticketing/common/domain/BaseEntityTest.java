package com.portfolio.ticketing.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BaseEntityTest {

  private static final class SampleEntity extends BaseEntity {}

  private static SampleEntity withId(UUID id) throws ReflectiveOperationException {
    SampleEntity entity = new SampleEntity();
    Field field = BaseEntity.class.getDeclaredField("id");
    field.setAccessible(true);
    field.set(entity, id);
    return entity;
  }

  @Test
  void should_beEqual_when_idsMatch() throws ReflectiveOperationException {
    UUID id = UUID.randomUUID();

    assertThat(withId(id)).isEqualTo(withId(id));
  }

  @Test
  void should_notBeEqual_when_idIsNull() {
    assertThat(new SampleEntity()).isNotEqualTo(new SampleEntity());
  }

  @Test
  void should_notBeEqual_when_comparedWithOtherType() throws ReflectiveOperationException {
    assertThat(withId(UUID.randomUUID())).isNotEqualTo("other");
  }

  @Test
  void should_beEqualToItself_when_transient() {
    SampleEntity entity = new SampleEntity();

    assertThat(entity).isEqualTo(entity).hasSameHashCodeAs(entity);
  }

  @Test
  void should_exposeNullAuditFields_when_notPersisted() {
    SampleEntity entity = new SampleEntity();

    assertThat(entity.getCreatedAt()).isNull();
  }
}
