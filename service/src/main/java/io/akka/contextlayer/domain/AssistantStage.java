package io.akka.contextlayer.domain;

/** DRAFT does nothing; SHADOW records what it would do; LIVE acts within its permissions. */
public enum AssistantStage {
  DRAFT,
  SHADOW,
  LIVE
}
