package org.enso.searcher

import org.enso.polyglot.Suggestion
import org.enso.polyglot.runtime.Runtime.Api.{
  ExportsUpdate,
  SuggestionUpdate,
  SuggestionsDatabaseAction
}
import org.enso.searcher.data.QueryResult

/** The object for accessing the suggestions database. */
trait SuggestionsRepo[F[_]] {

  /** Initialize the repo. */
  def init: F[Unit]

  /** Get current version of the repo. */
  def currentVersion: F[Long]

  /** Get all suggestions.
    *
    * @return the current database version and the list of suggestions
    */
  def getAll: F[(Long, Seq[SuggestionEntry])]

  /** Search suggestion by various parameters.
    *
    * @param module the module name search parameter
    * @param selfType the self types to search for, ordered by specificity with
    *                 the most specific type first
    * @param returnType the returnType search parameter
    * @param kinds the list suggestion kinds to search
    * @param position the absolute position in the text
    * @param isStatic the static attribute
    * @return the current database version and the list of found suggestion ids,
    *         ranked by specificity
    */
  def search(
    module: Option[String],
    selfType: Seq[String],
    returnType: Option[String],
    kinds: Option[Seq[Suggestion.Kind]],
    position: Option[Suggestion.Position],
    isStatic: Option[Boolean]
  ): F[(Long, Seq[Long])]

  /** Select the suggestion by id.
    *
    * @param id the id of a suggestion
    * @return return the suggestion
    */
  def select(id: Long): F[Option[Suggestion]]

  /** Insert the suggestion.
    *
    * @param suggestion the suggestion to insert
    * @return the id of an inserted suggestion
    */
  def insert(suggestion: Suggestion): F[Option[Long]]

  /** Insert a list of suggestions.
    *
    * @param suggestions the suggestions to insert
    * @return the current database version and a list of inserted suggestion ids
    */
  def insertAll(suggestions: Seq[Suggestion]): F[(Long, Seq[Long])]

  /** Apply suggestion updates.
    *
    * @param tree the sequence of suggestion updates
    * @return the result of applying the updates
    */
  def applyTree(
    tree: Seq[SuggestionUpdate]
  ): F[Seq[QueryResult[SuggestionUpdate]]]

  /** Apply the sequence of actions on the database.
    *
    * @param actions the list of actions
    * @return the result of applying the actions
    */
  def applyActions(
    actions: Seq[SuggestionsDatabaseAction]
  ): F[Seq[QueryResult[SuggestionsDatabaseAction]]]

  /** Apply the sequence of export updates on the database.
    *
    * @param updates the list of export updates
    * @return the result of applying the updates
    */
  def applyExports(
    updates: Seq[ExportsUpdate]
  ): F[Seq[QueryResult[ExportsUpdate]]]

  /** Remove the suggestion.
    *
    * @param suggestion the suggestion to remove
    * @return the id of removed suggestion
    */
  def remove(suggestion: Suggestion): F[Option[Long]]

  /** Remove suggestions by module names.
    *
    * @param modules the list of module names
    * @return the current database version and a list of removed suggestion ids
    */
  def removeModules(modules: Seq[String]): F[(Long, Seq[Long])]

  /** Update the suggestion.
    *
    * @param suggestion the key suggestion
    * @param externalId the external id to update
    * @param returnType the return type to update
    * @param documentation the documentation string to update
    * @param scope the scope to update
    */
  def update(
    suggestion: Suggestion,
    externalId: Option[Option[Suggestion.ExternalID]],
    returnType: Option[String],
    documentation: Option[Option[String]],
    scope: Option[Suggestion.Scope],
    reexport: Option[Option[String]]
  ): F[(Long, Option[Long])]

  /** Update a list of suggestions by external id.
    *
    * @param expressions pairs of external id and a return type
    * @return the current database version and a list of updated suggestion ids
    */
  def updateAll(
    expressions: Seq[(Suggestion.ExternalID, String)]
  ): F[(Long, Seq[Option[Long]])]

  /** Cleans the repo resetting the version. */
  def clean: F[Unit]
}
