<%@ page contentType="text/html;charset=UTF-8" %>
<!DOCTYPE html>
<html>
<head>
    <meta name="layout" content="${grailsApplication.config.skin.layout}" />
    <title>GBIF synchronisation</title>
    <g:if test="${run.status == 'RUNNING'}">
        <meta http-equiv="refresh" content="10" />
    </g:if>
    <style>
        .sync-summary span { display: inline-block; margin-right: 1.5em; font-size: 1.1em; }
        .sync-badge { display: inline-block; padding: 2px 10px; border-radius: 10px; color: #fff; font-size: 0.9em; }
        .sync-UPDATED { background: #3c763d; }
        .sync-RUNNING { background: #31708f; }
        .sync-UPDATE { background: #31708f; }
        .sync-FAILED { background: #a94442; }
        .sync-REFUSE { background: #8a6d3b; }
        .sync-SKIP { background: #777; }
        .sync-banner { padding: 10px 15px; border-radius: 4px; margin: 15px 0; }
        .sync-banner-running { background: #d9edf7; color: #31708f; }
        .sync-banner-done { background: #dff0d8; color: #3c763d; }
        td.sync-reason { font-size: 0.9em; color: #555; max-width: 420px; }
        .sync-polygon { background: #f0ad4e; }
        .sync-warning { padding: 10px 15px; border-radius: 4px; margin: 15px 0; background: #fcf8e3; color: #8a6d3b; border: 1px solid #faebcc; }
    </style>
</head>
<body>
<%
    def plan = run.plan ?: []
    def results = run.results ?: [:]
    def count = { String s -> results.values().count { it.status == s } }
    def updated = count('UPDATED')
    def failed = count('FAILED')
    def refused = plan.count { it.action == 'REFUSE' }
    def skipped = plan.count { it.action == 'SKIP' }
%>
<h1>GBIF synchronisation</h1>
<p class="lead">${run.label ?: 'Synchronisation run'}</p>

<g:if test="${run.status == 'RUNNING'}">
    <div class="sync-banner sync-banner-running">
        <strong>Running.</strong> Downloads from GBIF can take several minutes. This page refreshes every 10 seconds.
    </div>
</g:if>
<g:else>
    <div class="sync-banner sync-banner-done">
        <strong>Finished.</strong> Check each resource below. Failed resources were not changed.
    </div>
</g:else>

<div class="sync-summary">
    <span><strong>${plan.size()}</strong> resources checked</span>
    <span><strong>${updated}</strong> updated</span>
    <span><strong>${skipped}</strong> already up to date</span>
    <span><strong>${refused}</strong> refused</span>
    <span><strong>${failed}</strong> failed</span>
</div>

<g:if test="${plan.count { it.scope?.type == 'polygon' } > 0}">
    <p class="sync-warning">${plan.count { it.scope?.type == 'polygon' }} dataset(s) in this run were downloaded with a polygon filter (WKT), not a GBIF country or region code.</p>
</g:if>
<table class="table table-striped" style="margin-top: 15px">
    <thead>
    <tr>
        <th>Resource</th>
        <th>Area</th>
        <th>Status</th>
        <th>Details</th>
    </tr>
    </thead>
    <tbody>
    <g:each in="${plan}" var="item">
        <%
            def result = results[item.uid]
            def status = result?.status ?: item.action
            def area = [gadm: "Region ${item.scope?.value}", country: "${item.scope?.value} (whole country)", polygon: 'Polygon (WKT)', none: 'No area filter'][item.scope?.type] ?: 'No area filter'
            def details = result?.message ?: item.reason
        %>
        <tr>
            <td>
                <g:link controller="dataResource" action="show" id="${item.uid}">${item.name ?: item.uid}</g:link>
                <br/><small>${item.uid}</small>
            </td>
            <td>${area}<g:if test="${item.scope?.type == 'polygon'}"> <span class="sync-badge sync-polygon">POLYGON</span></g:if></td>
            <td><span class="sync-badge sync-${status}">${status}</span></td>
            <td class="sync-reason">${details}</td>
        </tr>
    </g:each>
    <g:if test="${plan.isEmpty()}">
        <tr><td colspan="4">No resources in this run.</td></tr>
    </g:if>
    </tbody>
</table>

<p>
    <small>Started ${g.formatDate(date: run.startedAt, format: 'yyyy-MM-dd HH:mm:ss')}. Raw data: <g:link action="syncStatus" params="[loadGuid: run.loadGuid, format: 'json']">JSON</g:link>.</small>
</p>
</body>
</html>
