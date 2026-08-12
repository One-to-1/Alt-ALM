# Appendix — Full deduped resource-list table (§3a of probe3-mining-swagger.md)

Source: `tests/fixtures/resource-list-site.json`. 319 `BasePath` groups, 447 unique
`(BasePath, Path)` combinations, listing all HTTP methods observed per path (comma-joined) and
flagging `**[DEPRECATED]**` where any duplicate entry for that path carried `Deprecated: true`.
Generated via PowerShell `ConvertFrom-Json` aggregation — see probe3-mining-swagger.md §3(a) for
methodology and headline stats (17 deprecated ops, all `POST .../{parent_entity_id}/attachments`
variants).

---

### BasePath: `ali/plugin-info`
- `/ali/plugin-info` — GET
### BasePath: `ali/version-info`
- `/ali/version-info` — GET
### BasePath: `domains`
- `/domains` — GET
- `/domains/{domain}/projects` — GET
### BasePath: `domains/{domain}/projects/{project}/analysis-item-file`
- `/domains/{domain}/projects/{project}/analysis-item-file` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/analysis-item-files`
- `/domains/{domain}/projects/{project}/analysis-item-files` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/analysis-item-files/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/analysis-item-files/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/analysis-item-files/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/analysis-item-files/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/analysis-item-files/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/analysis-item-folders`
- `/domains/{domain}/projects/{project}/analysis-item-folders` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/analysis-item-folders/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/analysis-items`
- `/domains/{domain}/projects/{project}/analysis-items` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/analysis-items/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/analysis-segments`
- `/domains/{domain}/projects/{project}/analysis-segments` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/analysis-segments/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/attachments`
- `/domains/{domain}/projects/{project}/attachments` — GET,POST
- `/domains/{domain}/projects/{project}/attachments/{attachment_id}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/audits`
- `/domains/{domain}/projects/{project}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/bpm-folders`
- `/domains/{domain}/projects/{project}/bpm-folders` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/bpm-folders/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/bpm-folders/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/bpm-folders/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/bpm-folders/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/bpm-folders/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/bpm-folders/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/bpm-folders/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/bpm-folders/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/branch-policy-links`
- `/domains/{domain}/projects/{project}/branch-policy-links` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/branch-policy-links/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/branch-policy-links/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/branch-policy-links/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/branch-policy-links/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/branch-policy-links/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/build-artifacts`
- `/domains/{domain}/projects/{project}/build-artifacts` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/build-artifacts/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/build-artifacts/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/build-artifacts/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/build-artifacts/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/build-artifacts/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/build-artifacts/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/build-artifacts/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/build-code-refs`
- `/domains/{domain}/projects/{project}/build-code-refs` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/build-code-refs/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/build-code-refs/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/build-code-refs/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/build-code-refs/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/build-code-refs/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/build-code-refs/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/build-code-refs/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/build-contexts`
- `/domains/{domain}/projects/{project}/build-contexts` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/build-contexts/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/build-contexts/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/build-contexts/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/build-contexts/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/build-contexts/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/build-instances`
- `/domains/{domain}/projects/{project}/build-instances` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/build-instances/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/build-instances/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/build-instances/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/build-instances/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/build-instances/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/build-instances/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/build-instances/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/build-instances/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/build-instances/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/build-servers`
- `/domains/{domain}/projects/{project}/build-servers` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/build-servers/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/build-servers/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/build-servers/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/build-servers/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/build-servers/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/build-servers/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/build-servers/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/build-types`
- `/domains/{domain}/projects/{project}/build-types` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/build-types/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/build-types/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/build-types/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/build-types/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/build-types/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/build-types/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/build-types/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/businessmodels`
- `/domains/{domain}/projects/{project}/businessmodels` — GET
### BasePath: `domains/{domain}/projects/{project}/businessviews`
- `/domains/{domain}/projects/{project}/businessviews` — GET
### BasePath: `domains/{domain}/projects/{project}/businessviews/{view_name}`
- `/domains/{domain}/projects/{project}/businessviews/{view_name}` — GET
### BasePath: `domains/{domain}/projects/{project}/bv-hosts`
- `/domains/{domain}/projects/{project}/bv-hosts` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/bv-hosts/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/bv-hosts/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/bv-hosts/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/bv-hosts/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/bv-hosts/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/bvexcel/abort-task-execute`
- `/domains/{domain}/projects/{project}/bvexcel/abort-task-execute` — POST
### BasePath: `domains/{domain}/projects/{project}/bvexcel/configuration-execution-metadata`
- `/domains/{domain}/projects/{project}/bvexcel/configuration-execution-metadata` — POST
### BasePath: `domains/{domain}/projects/{project}/bvexcel/dql-execution-metadata`
- `/domains/{domain}/projects/{project}/bvexcel/dql-execution-metadata` — POST
### BasePath: `domains/{domain}/projects/{project}/bvexcel/execute-configuration`
- `/domains/{domain}/projects/{project}/bvexcel/execute-configuration` — POST
### BasePath: `domains/{domain}/projects/{project}/bvexcel/execute-dql`
- `/domains/{domain}/projects/{project}/bvexcel/execute-dql` — POST
### BasePath: `domains/{domain}/projects/{project}/bvexcel/resultfile`
- `/domains/{domain}/projects/{project}/bvexcel/resultfile` — GET
- `/domains/{domain}/projects/{project}/bvexcel/resultfile/decline-download` — POST
### BasePath: `domains/{domain}/projects/{project}/bvexcel/start-execute-configuration`
- `/domains/{domain}/projects/{project}/bvexcel/start-execute-configuration` — POST
### BasePath: `domains/{domain}/projects/{project}/bvexcel/start-execute-dql`
- `/domains/{domain}/projects/{project}/bvexcel/start-execute-dql` — POST
### BasePath: `domains/{domain}/projects/{project}/bvexcel/task-details`
- `/domains/{domain}/projects/{project}/bvexcel/task-details` — GET
### BasePath: `domains/{domain}/projects/{project}/bvexcel/translate-configuration-to-dql`
- `/domains/{domain}/projects/{project}/bvexcel/translate-configuration-to-dql` — POST
### BasePath: `domains/{domain}/projects/{project}/bvexcel/validate-dql`
- `/domains/{domain}/projects/{project}/bvexcel/validate-dql` — POST
### BasePath: `domains/{domain}/projects/{project}/changeset-files`
- `/domains/{domain}/projects/{project}/changeset-files` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/changeset-files/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/changeset-files/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/changeset-files/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/changeset-files/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/changeset-files/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/changeset-files/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/changeset-files/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/changeset-link-associations`
- `/domains/{domain}/projects/{project}/changeset-link-associations` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/changeset-link-associations/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/changeset-link-associations/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/changeset-link-associations/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/changeset-link-associations/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/changeset-link-associations/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/changesets`
- `/domains/{domain}/projects/{project}/changesets` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/changesets/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/changesets/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/changesets/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/changesets/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/changesets/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/changesets/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/changesets/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/changesets/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/changesets/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/components/{parent_entity_id: [0-9]+}/snapshot`
- `/domains/{domain}/projects/{project}/components/{parent_entity_id: [0-9]+}/snapshot` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/customization/entities`
- `/domains/{domain}/projects/{project}/customization/entities` — GET
- `/domains/{domain}/projects/{project}/customization/entities/{entity-name}` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/entities/{entity-name}/fields`
- `/domains/{domain}/projects/{project}/customization/entities/{entity-name}/fields` — GET,PUT
### BasePath: `domains/{domain}/projects/{project}/customization/entities/{entity-name}/lists`
- `/domains/{domain}/projects/{project}/customization/entities/{entity-name}/lists` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/entities/{entity-name}/permissions`
- `/domains/{domain}/projects/{project}/customization/entities/{entity-name}/permissions` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/entities/{entity-name}/relations`
- `/domains/{domain}/projects/{project}/customization/entities/{entity-name}/relations` — GET
- `/domains/{domain}/projects/{project}/customization/entities/{entity-name}/relations/{relationName}` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/entities/{entity-name}/types`
- `/domains/{domain}/projects/{project}/customization/entities/{entity-name}/types` — GET,PUT
### BasePath: `domains/{domain}/projects/{project}/customization/entities/{entity-name}/types/{type_id}/fields`
- `/domains/{domain}/projects/{project}/customization/entities/{entity-name}/types/{type_id}/fields` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/entities/{entity-name}/types/{type_id}/icon`
- `/domains/{domain}/projects/{project}/customization/entities/{entity-name}/types/{type_id}/icon` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/extensions`
- `/domains/{domain}/projects/{project}/customization/extensions` — GET
- `/domains/{domain}/projects/{project}/customization/extensions/{extension_name}` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/extensions/dev/`
- `/domains/{domain}/projects/{project}/customization/extensions/dev/` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/extensions/dev/preferences`
- `/domains/{domain}/projects/{project}/customization/extensions/dev/preferences` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/extensions/dev/workflow`
- `/domains/{domain}/projects/{project}/customization/extensions/dev/workflow` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/groups`
- `/domains/{domain}/projects/{project}/customization/groups` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/lists`
- `/domains/{domain}/projects/{project}/customization/lists` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/project-access-data`
- `/domains/{domain}/projects/{project}/customization/project-access-data` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/relations`
- `/domains/{domain}/projects/{project}/customization/relations` — GET
- `/domains/{domain}/projects/{project}/customization/relations/{relationName}` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/used-lists`
- `/domains/{domain}/projects/{project}/customization/used-lists` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/usergroups/`
- `/domains/{domain}/projects/{project}/customization/usergroups/{user-name}` — GET
### BasePath: `domains/{domain}/projects/{project}/customization/users`
- `/domains/{domain}/projects/{project}/customization/users` — GET
- `/domains/{domain}/projects/{project}/customization/users/{user-name}` — GET,PUT
- `/domains/{domain}/projects/{project}/customization/users/{user-name}/avatar` — GET,POST
### BasePath: `domains/{domain}/projects/{project}/dashboard-folders`
- `/domains/{domain}/projects/{project}/dashboard-folders` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/dashboard-folders/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/dashboard-folders/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/dashboard-folders/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/dashboard-folders/copy`
- `/domains/{domain}/projects/{project}/dashboard-folders/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/dashboard-pages`
- `/domains/{domain}/projects/{project}/dashboard-pages` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/dashboard-pages/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/dashboard-pages/{parent_entity_id: [0-9]+}/items`
- `/domains/{domain}/projects/{project}/dashboard-pages/{parent_entity_id: [0-9]+}/items` — GET,POST
- `/domains/{domain}/projects/{project}/dashboard-pages/{parent_entity_id: [0-9]+}/items/{id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/dashboard-pages/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/dashboard-pages/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/dashboard-pages/copy`
- `/domains/{domain}/projects/{project}/dashboard-pages/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/dashboardpages/{entity_id : [0-9]+}/layouts`
- `/domains/{domain}/projects/{project}/dashboardpages/{entity_id : [0-9]+}/layouts` — GET
### BasePath: `domains/{domain}/projects/{project}/dashboardpages/{entity_id : [0-9]+}/layouts/{layout_name}`
- `/domains/{domain}/projects/{project}/dashboardpages/{entity_id : [0-9]+}/layouts/{layout_name}` — GET
### BasePath: `domains/{domain}/projects/{project}/defect-links`
- `/domains/{domain}/projects/{project}/defect-links` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/defect-links/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/defect-links/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/defect-links/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/defects`
- `/domains/{domain}/projects/{project}/defects` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/defects/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/defects/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/defects/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/defects/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/defects/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/defects/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/defects/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/defects/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/defects/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/defects/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/defects/copy`
- `/domains/{domain}/projects/{project}/defects/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/defects/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/defects/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/design-steps`
- `/domains/{domain}/projects/{project}/design-steps` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/design-steps/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/design-steps/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/design-steps/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/design-steps/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/design-steps/{parent_entity_id: [0-9]+}/step-parameters`
- `/domains/{domain}/projects/{project}/design-steps/{parent_entity_id: [0-9]+}/step-parameters` — GET,POST
- `/domains/{domain}/projects/{project}/design-steps/{parent_entity_id: [0-9]+}/step-parameters/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/design-steps/copy`
- `/domains/{domain}/projects/{project}/design-steps/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/dqldescriptor`
- `/domains/{domain}/projects/{project}/dqldescriptor` — GET
### BasePath: `domains/{domain}/projects/{project}/environments`
- `/domains/{domain}/projects/{project}/environments` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/environments/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/environments/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/environments/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/environments/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/environments/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/environments/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/environments/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/environments/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/environments/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/environments/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/environments/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/environments/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/export/{entity-collection}`
- `/domains/{domain}/projects/{project}/export/{entity-collection}` — GET
- `/domains/{domain}/projects/{project}/export/{entity-collection}/{entity_id : [0-9]+}` — GET
### BasePath: `domains/{domain}/projects/{project}/favorite-folders`
- `/domains/{domain}/projects/{project}/favorite-folders` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/favorite-folders/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/favorite-folders/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/favorite-folders/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/favorites`
- `/domains/{domain}/projects/{project}/favorites` — GET,POST
- `/domains/{domain}/projects/{project}/favorites/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/graphs/{entity_id : [0-9]+}/layouts`
- `/domains/{domain}/projects/{project}/graphs/{entity_id : [0-9]+}/layouts` — GET
### BasePath: `domains/{domain}/projects/{project}/graphs/{entity_id : [0-9]+}/layouts/{layout_name}`
- `/domains/{domain}/projects/{project}/graphs/{entity_id : [0-9]+}/layouts/{layout_name}` — GET
### BasePath: `domains/{domain}/projects/{project}/graphs/{entity_id : [0-9]+}/result`
- `/domains/{domain}/projects/{project}/graphs/{entity_id : [0-9]+}/result` — GET
### BasePath: `domains/{domain}/projects/{project}/graphs/{entity_id : [0-9]+}/type-descriptor`
- `/domains/{domain}/projects/{project}/graphs/{entity_id : [0-9]+}/type-descriptor` — GET
### BasePath: `domains/{domain}/projects/{project}/host-groups`
- `/domains/{domain}/projects/{project}/host-groups` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/host-groups/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/host-groups/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/host-groups/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/host-groups/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/host-groups/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/host-in-group`
- `/domains/{domain}/projects/{project}/host-in-group` — GET
### BasePath: `domains/{domain}/projects/{project}/internal-token`
- `/domains/{domain}/projects/{project}/internal-token` — GET
### BasePath: `domains/{domain}/projects/{project}/lab-runs-protocol-granularities`
- `/domains/{domain}/projects/{project}/lab-runs-protocol-granularities` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/lab-runs-protocol-granularities/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/lab-runs-protocol-granularities/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/lab-runs-protocol-granularities/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/lab-runs-protocol-granularities/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/lab-runs-protocol-granularities/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/lab-runs-protocol-granularities/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/lab-runs-protocol-granularities/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/list-items`
- `/domains/{domain}/projects/{project}/list-items` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/list-items/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/list-items/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/list-items/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/list-items/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/list-items/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/list-items/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/list-items/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/locks`
- `/domains/{domain}/projects/{project}/locks` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/locks/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/locks/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/locks/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/milestones`
- `/domains/{domain}/projects/{project}/milestones` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/milestones/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/milestones/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/milestones/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/milestones/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/milestones/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/milestones/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/milestones/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/milestones/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/milestones/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/milestones/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/milestones/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/milestones/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/policy-items`
- `/domains/{domain}/projects/{project}/policy-items` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/policy-items/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/policy-items/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/policy-items/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/policy-items/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/policy-items/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/project-connection`
- `/domains/{domain}/projects/{project}/project-connection` — DELETE,POST
### BasePath: `domains/{domain}/projects/{project}/release-cycles`
- `/domains/{domain}/projects/{project}/release-cycles` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/release-cycles/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/release-cycles/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/release-cycles/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/release-cycles/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/release-cycles/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/release-cycles/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/release-folders`
- `/domains/{domain}/projects/{project}/release-folders` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/release-folders/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/release-folders/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/release-folders/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/release-folders/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/release-folders/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/release-folders/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/releases`
- `/domains/{domain}/projects/{project}/releases` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/releases/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/releases/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/releases/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/releases/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/releases/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/releases/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/reports/{entity_id : \-?[0-9]+}`
- `/domains/{domain}/projects/{project}/reports/{entity_id : \-?[0-9]+}` — GET
- `/domains/{domain}/projects/{project}/reports/{entity_id : \-?[0-9]+}/{parent_entity_name}/{parent_entity_id: [0-9]+}/attachments/{attachment_id}` — GET
- `/domains/{domain}/projects/{project}/reports/{entity_id : \-?[0-9]+}/results/{result_id}/files/{file_id}` — GET
### BasePath: `domains/{domain}/projects/{project}/req-traces`
- `/domains/{domain}/projects/{project}/req-traces` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/req-traces/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/req-traces/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/req-traces/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/requirement-coverages`
- `/domains/{domain}/projects/{project}/requirement-coverages` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/requirement-coverages/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/requirement-target-cycles`
- `/domains/{domain}/projects/{project}/requirement-target-cycles` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/requirement-target-cycles/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/requirement-target-releases`
- `/domains/{domain}/projects/{project}/requirement-target-releases` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/requirement-target-releases/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/requirements`
- `/domains/{domain}/projects/{project}/requirements` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/requirements/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/requirements/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/requirements/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/requirements/{entity_id : [0-9]+}/tree`
- `/domains/{domain}/projects/{project}/requirements/{entity_id : [0-9]+}/tree` — GET
### BasePath: `domains/{domain}/projects/{project}/requirements/{entity_id : [0-9]+}/versions`
- `/domains/{domain}/projects/{project}/requirements/{entity_id : [0-9]+}/versions` — GET
- `/domains/{domain}/projects/{project}/requirements/{entity_id : [0-9]+}/versions/{version_number}` — GET
- `/domains/{domain}/projects/{project}/requirements/{entity_id : [0-9]+}/versions/check-in` — POST
- `/domains/{domain}/projects/{project}/requirements/{entity_id : [0-9]+}/versions/check-out` — POST
- `/domains/{domain}/projects/{project}/requirements/{entity_id : [0-9]+}/versions/undo-check-out` — POST
### BasePath: `domains/{domain}/projects/{project}/requirements/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/requirements/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/requirements/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/requirements/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/requirements/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/requirements/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/requirements/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/requirements/copy`
- `/domains/{domain}/projects/{project}/requirements/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/requirements/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/requirements/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/resource-folders`
- `/domains/{domain}/projects/{project}/resource-folders` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/resource-folders/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/resource-folders/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/resource-folders/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/resource-folders/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/resource-folders/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/resource-folders/copy`
- `/domains/{domain}/projects/{project}/resource-folders/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/resources`
- `/domains/{domain}/projects/{project}/resources` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/assets-relations`
- `/domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/assets-relations` — DELETE,GET,POST
- `/domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/assets-relations/{relation_id: [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/versions`
- `/domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/versions` — GET
- `/domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/versions/{version_number}` — GET
- `/domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/versions/check-in` — POST
- `/domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/versions/check-out` — POST
- `/domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/versions/undo-check-out` — POST
### BasePath: `domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/versions/{version_number}/assets-relations`
- `/domains/{domain}/projects/{project}/resources/{entity_id : [0-9]+}/versions/{version_number}/assets-relations` — GET
### BasePath: `domains/{domain}/projects/{project}/resources/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/resources/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/resources/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/resources/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/resources/copy`
- `/domains/{domain}/projects/{project}/resources/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/results`
- `/domains/{domain}/projects/{project}/results` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/results/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/results/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/results/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/results/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/results/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/results/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/results/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/run-steps`
- `/domains/{domain}/projects/{project}/run-steps` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/run-steps/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/run-steps/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/run-steps/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/run-steps/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/runs`
- `/domains/{domain}/projects/{project}/runs` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/runs/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/runs/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/runs/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/run-steps`
- `/domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/run-steps` — GET,POST
- `/domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/run-steps/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/step-parameters`
- `/domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/step-parameters` — GET,POST
- `/domains/{domain}/projects/{project}/runs/{parent_entity_id: [0-9]+}/step-parameters/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/runs/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/runs/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/scm-branch-releases`
- `/domains/{domain}/projects/{project}/scm-branch-releases` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/scm-branch-releases/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/scm-branch-releases/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/scm-branch-releases/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/scm-branch-releases/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/scm-branch-releases/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/scm-branchs`
- `/domains/{domain}/projects/{project}/scm-branchs` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/scm-branchs/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/scm-branchs/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/scm-branchs/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/scm-branchs/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/scm-branchs/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/scm-branchs/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/scm-branchs/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/scm-repositorys`
- `/domains/{domain}/projects/{project}/scm-repositorys` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/scm-repositorys/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/scm-repositorys/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/scm-repositorys/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/scm-repositorys/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/scm-repositorys/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/scm-repositorys/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/scm-repositorys/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/active-users`
- `/domains/{domain}/projects/{project}/scm/active-users` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/branch-check`
- `/domains/{domain}/projects/{project}/scm/branch-check` — POST
### BasePath: `domains/{domain}/projects/{project}/scm/build-configuration-check`
- `/domains/{domain}/projects/{project}/scm/build-configuration-check` — POST
### BasePath: `domains/{domain}/projects/{project}/scm/build-configuration-list`
- `/domains/{domain}/projects/{project}/scm/build-configuration-list/{buildServerId : [0-9]+}` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/build-configuration-scm`
- `/domains/{domain}/projects/{project}/scm/build-configuration-scm/{buildTypeId : [0-9]+}` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/build-providers`
- `/domains/{domain}/projects/{project}/scm/build-providers` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/build-providers/{provider-type}/properties`
- `/domains/{domain}/projects/{project}/scm/build-providers/{provider-type}/properties` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/build-push-service`
- `/domains/{domain}/projects/{project}/scm/build-push-service` — POST
- `/domains/{domain}/projects/{project}/scm/build-push-service/{buildId : [0-9]+}/code-changes` — POST
- `/domains/{domain}/projects/{project}/scm/build-push-service/{buildId : [0-9]+}/coverage` — POST
- `/domains/{domain}/projects/{project}/scm/build-push-service/{buildId : [0-9]+}/test-results` — POST
### BasePath: `domains/{domain}/projects/{project}/scm/build-server-check`
- `/domains/{domain}/projects/{project}/scm/build-server-check` — POST
### BasePath: `domains/{domain}/projects/{project}/scm/build-status`
- `/domains/{domain}/projects/{project}/scm/build-status` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/build-summary`
- `/domains/{domain}/projects/{project}/scm/build-summary` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/build-tests`
- `/domains/{domain}/projects/{project}/scm/build-tests/{buildId : [0-9]+}` — GET
- `/domains/{domain}/projects/{project}/scm/build-tests/{buildId : [0-9]+}/group-by/{groupBy}` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/commit-service`
- `/domains/{domain}/projects/{project}/scm/commit-service` — POST
### BasePath: `domains/{domain}/projects/{project}/scm/enforcement-service`
- `/domains/{domain}/projects/{project}/scm/enforcement-service` — POST
### BasePath: `domains/{domain}/projects/{project}/scm/file-diff`
- `/domains/{domain}/projects/{project}/scm/file-diff` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/file-view`
- `/domains/{domain}/projects/{project}/scm/file-view` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/metric-report`
- `/domains/{domain}/projects/{project}/scm/metric-report` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/pattern-converter`
- `/domains/{domain}/projects/{project}/scm/pattern-converter` — POST
### BasePath: `domains/{domain}/projects/{project}/scm/pattern-service`
- `/domains/{domain}/projects/{project}/scm/pattern-service` — POST
### BasePath: `domains/{domain}/projects/{project}/scm/providers`
- `/domains/{domain}/projects/{project}/scm/providers` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/providers/{provider-type}/properties`
- `/domains/{domain}/projects/{project}/scm/providers/{provider-type}/properties` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/release-build-status`
- `/domains/{domain}/projects/{project}/scm/release-build-status` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/report`
- `/domains/{domain}/projects/{project}/scm/report` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/report-build`
- `/domains/{domain}/projects/{project}/scm/report-build` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/report-builds`
- `/domains/{domain}/projects/{project}/scm/report-builds` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/report-graph`
- `/domains/{domain}/projects/{project}/scm/report-graph` — GET
### BasePath: `domains/{domain}/projects/{project}/scm/repository-check`
- `/domains/{domain}/projects/{project}/scm/repository-check` — POST
### BasePath: `domains/{domain}/projects/{project}/scm/task-executor`
- `/domains/{domain}/projects/{project}/scm/task-executor` — POST
### BasePath: `domains/{domain}/projects/{project}/step-parameters`
- `/domains/{domain}/projects/{project}/step-parameters` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/step-parameters/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-config-coverages`
- `/domains/{domain}/projects/{project}/test-config-coverages` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/test-config-coverages/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-config-coverages/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/test-config-coverages/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/test-config-coverages/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/test-config-coverages/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/test-config-coverages/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/test-config-coverages/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/test-configs`
- `/domains/{domain}/projects/{project}/test-configs` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/test-configs/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-configs/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/test-configs/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/test-configs/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/test-configs/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/test-configs/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-configs/{parent_entity_id: [0-9]+}/step-parameters`
- `/domains/{domain}/projects/{project}/test-configs/{parent_entity_id: [0-9]+}/step-parameters` — GET,POST
- `/domains/{domain}/projects/{project}/test-configs/{parent_entity_id: [0-9]+}/step-parameters/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-configs/copy`
- `/domains/{domain}/projects/{project}/test-configs/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/test-configs/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/test-configs/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/test-criterion-coverages`
- `/domains/{domain}/projects/{project}/test-criterion-coverages` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/test-criterion-coverages/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-criterion-coverages/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/test-criterion-coverages/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/test-criterion-coverages/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/test-criterion-coverages/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/test-criterion-coverages/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/test-criterion-coverages/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/test-criterions`
- `/domains/{domain}/projects/{project}/test-criterions` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/test-criterions/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-executions`
- `/domains/{domain}/projects/{project}/test-executions` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/test-executions/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-executions/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/test-executions/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/test-executions/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/test-executions/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/test-executions/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/test-executions/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/test-folders`
- `/domains/{domain}/projects/{project}/test-folders` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/test-folders/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-folders/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/test-folders/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/test-folders/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/test-folders/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/test-folders/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-folders/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/test-folders/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/test-folders/copy`
- `/domains/{domain}/projects/{project}/test-folders/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/test-instances`
- `/domains/{domain}/projects/{project}/test-instances` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/test-instances/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-instances/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/test-instances/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/test-instances/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/test-instances/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/test-instances/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-instances/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/test-instances/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/test-instances/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/test-instances/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/test-instances/{parent_entity_id: [0-9]+}/step-parameters`
- `/domains/{domain}/projects/{project}/test-instances/{parent_entity_id: [0-9]+}/step-parameters` — GET,POST
- `/domains/{domain}/projects/{project}/test-instances/{parent_entity_id: [0-9]+}/step-parameters/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-instances/copy`
- `/domains/{domain}/projects/{project}/test-instances/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/test-instances/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/test-instances/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/test-parameters`
- `/domains/{domain}/projects/{project}/test-parameters` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/test-parameters/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-parameters/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/test-parameters/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/test-set-folders`
- `/domains/{domain}/projects/{project}/test-set-folders` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/test-set-folders/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-set-folders/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/test-set-folders/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/test-set-folders/{entity_id : [0-9]+}/statistics`
- `/domains/{domain}/projects/{project}/test-set-folders/{entity_id : [0-9]+}/statistics` — GET
### BasePath: `domains/{domain}/projects/{project}/test-set-folders/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/test-set-folders/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/test-set-folders/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-set-folders/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/test-set-folders/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/test-set-folders/copy`
- `/domains/{domain}/projects/{project}/test-set-folders/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/test-sets`
- `/domains/{domain}/projects/{project}/test-sets` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/test-sets/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-sets/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/test-sets/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/test-sets/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/test-sets/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/test-sets/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/test-sets/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/test-sets/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/test-sets/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/test-sets/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/test-sets/copy`
- `/domains/{domain}/projects/{project}/test-sets/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/test-sets/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/test-sets/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/tests`
- `/domains/{domain}/projects/{project}/tests` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/assets-relations`
- `/domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/assets-relations` — DELETE,GET,POST
- `/domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/assets-relations/{relation_id: [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/audits`
- `/domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/audits` — GET
### BasePath: `domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/deleted-assets-infos`
- `/domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/deleted-assets-infos` — GET
### BasePath: `domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/versions`
- `/domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/versions` — GET
- `/domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/versions/{version_number}` — GET
- `/domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/versions/check-in` — POST
- `/domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/versions/check-out` — POST
- `/domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/versions/undo-check-out` — POST
### BasePath: `domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/versions/{version_number}/assets-relations`
- `/domains/{domain}/projects/{project}/tests/{entity_id : [0-9]+}/versions/{version_number}/assets-relations` — GET
### BasePath: `domains/{domain}/projects/{project}/tests/{parent_entity_id: [0-9]+}/attachments`
- `/domains/{domain}/projects/{project}/tests/{parent_entity_id: [0-9]+}/attachments` — GET,POST **[DEPRECATED]**
- `/domains/{domain}/projects/{project}/tests/{parent_entity_id: [0-9]+}/attachments/{attachment_name}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/tests/{parent_entity_id: [0-9]+}/lock`
- `/domains/{domain}/projects/{project}/tests/{parent_entity_id: [0-9]+}/lock` — DELETE,GET,POST
### BasePath: `domains/{domain}/projects/{project}/tests/{parent_entity_id: [0-9]+}/mail`
- `/domains/{domain}/projects/{project}/tests/{parent_entity_id: [0-9]+}/mail` — POST
### BasePath: `domains/{domain}/projects/{project}/tests/{parent_entity_id: [0-9]+}/test-parameters`
- `/domains/{domain}/projects/{project}/tests/{parent_entity_id: [0-9]+}/test-parameters` — GET,POST
- `/domains/{domain}/projects/{project}/tests/{parent_entity_id: [0-9]+}/test-parameters/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/tests/copy`
- `/domains/{domain}/projects/{project}/tests/copy` — POST
### BasePath: `domains/{domain}/projects/{project}/tests/groups/{groupsFields}`
- `/domains/{domain}/projects/{project}/tests/groups/{groupsFields}` — GET
### BasePath: `domains/{domain}/projects/{project}/web-workflow-script`
- `/domains/{domain}/projects/{project}/web-workflow-script` — GET,PUT
- `/domains/{domain}/projects/{project}/web-workflow-script/properties` — GET
### BasePath: `domains/{domain}/projects/{project}/workspace-folders`
- `/domains/{domain}/projects/{project}/workspace-folders` — DELETE,GET,POST,PUT
- `/domains/{domain}/projects/{project}/workspace-folders/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/workspaces`
- `/domains/{domain}/projects/{project}/workspaces` — GET,POST
- `/domains/{domain}/projects/{project}/workspaces/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `domains/{domain}/projects/{project}/workspaces/{parent_entity_id: [0-9]+}/workspace-shares`
- `/domains/{domain}/projects/{project}/workspaces/{parent_entity_id: [0-9]+}/workspace-shares` — GET,POST
- `/domains/{domain}/projects/{project}/workspaces/{parent_entity_id: [0-9]+}/workspace-shares/{entity_id : [0-9]+}` — DELETE,GET,PUT
### BasePath: `is-authenticated`
- `/is-authenticated` — GET
### BasePath: `launcher/install-tokens`
- `/launcher/install-tokens` — POST
- `/launcher/install-tokens/{uniqueId}` — GET
### BasePath: `public/domains/{domain}/projects/{project}/dashboardpages/{entity_id : [0-9]+}/layouts/{layout_name}`
- `/public/domains/{domain}/projects/{project}/dashboardpages/{entity_id : [0-9]+}/layouts/{layout_name}` — GET
### BasePath: `public/domains/{domain}/projects/{project}/graphs/{entity_id : [0-9]+}/layouts/{layout_name}`
- `/public/domains/{domain}/projects/{project}/graphs/{entity_id : [0-9]+}/layouts/{layout_name}` — GET
### BasePath: `public/domains/{domain}/projects/{project}/reports/{entity_id : \-?[0-9]+}`
- `/public/domains/{domain}/projects/{project}/reports/{entity_id : \-?[0-9]+}` — GET
- `/public/domains/{domain}/projects/{project}/reports/{entity_id : \-?[0-9]+}/{parent_entity_name}/{parent_entity_id: [0-9]+}/attachments/{attachment_id}` — GET
- `/public/domains/{domain}/projects/{project}/reports/{entity_id : \-?[0-9]+}/results/{result_id}/files/{file_id}` — GET
### BasePath: `resource-list`
- `/resource-list` — GET
- `/resource-list/administrative` — GET
### BasePath: `sa/site-params`
- `/sa/site-params/metadata` — GET
### BasePath: `sa/version`
- `/sa/version` — GET
### BasePath: `server`
- `/server` — GET
- `/server/time` — GET
### BasePath: `site-session`
- `/site-session` — DELETE,GET,POST,PUT
### BasePath: `sso/initiations`
- `/sso/initiations` — GET
- `/sso/initiations/{uniqueId}` — GET
- `/sso/initiations/{uniqueId}/sso-confirmations` — GET
- `/sso/initiations/{uniqueId}/sso-validations` — GET
### BasePath: `synchronization`
- `/synchronization/check-entity-existence` — PUT
- `/synchronization/entity-type-last-touch-time` — GET
- `/synchronization/last-deleted-ids` — GET
- `/synchronization/start-tracking-times` — GET
- `/synchronization/synchronized-projects` — GET,PUT
