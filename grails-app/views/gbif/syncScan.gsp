<%@ page contentType="text/html;charset=UTF-8" %>
<!DOCTYPE html>
<html>
<head>
    <meta name="layout" content="${grailsApplication.config.skin.layout}" />
    <title>Automatic GBIF update</title>
    <style>
        .sync-option { border: 1px solid #ddd; border-radius: 6px; padding: 15px; margin-bottom: 15px; }
        .sync-option h4 { margin-top: 0; }
        .sync-option p.sync-hint { color: #666; font-size: 0.9em; margin-bottom: 8px; }
        .sync-flash { padding: 10px 15px; border-radius: 4px; background: #fcf8e3; color: #8a6d3b; margin-bottom: 15px; }
    </style>
</head>
<body>
<h1>Automatic GBIF update</h1>
<p class="lead">Reload the datasets that GBIF has republished since the last update. Each dataset keeps its original area.</p>

<g:if test="${flash.message}">
    <div class="sync-flash">${flash.message}</div>
</g:if>

<g:form action="launchScan" controller="gbif" method="get">
    <div class="sync-option">
        <label>
            <input type="radio" name="method" value="provider" checked="checked" />
            <strong>1. One data provider</strong>
        </label>
        <p class="sync-hint">Updates every resource attached to the chosen provider.</p>
        <g:select name="providerUid" class="form-control" from="${providers}" optionKey="uid" optionValue="name"
                  noSelection="['':'Choose a data provider...']" />
    </div>

    <div class="sync-option">
        <label>
            <input type="radio" name="method" value="orphans" />
            <strong>2. Resources with no data provider</strong>
        </label>
        <p class="sync-hint">Updates every resource that has a GBIF key and no provider.
            Currently <strong>${orphanCount}</strong> resource(s) match.</p>
    </div>

    <div class="sync-option">
        <label>
            <input type="radio" name="method" value="resources" />
            <strong>3. Selected data resources</strong>
        </label>
        <p class="sync-hint">Hold Ctrl (or Cmd on a Mac) to select several resources.</p>
        <g:select name="resourceUids" class="form-control" from="${resources}" optionKey="uid" optionValue="name"
                  multiple="multiple" size="12" />
    </div>

    <div class="sync-option">
        <label>
            <input type="radio" name="method" value="all" />
            <strong>4. All data resources</strong>
        </label>
        <p class="sync-hint">Updates every resource that has a GBIF key, in one run. This can take hours.
            Currently <strong>${allCount}</strong> resource(s) match.</p>
    </div>

    <p>
        <button type="submit" class="btn btn-primary">Launch the update</button>
        <g:link controller="manage" action="list" class="btn btn-default">Back to data management</g:link>
    </p>
    <p><small>The update opens the results page. It may take several minutes because GBIF prepares each download.</small></p>
</g:form>
<script type="text/javascript">
    document.querySelector('form').addEventListener('submit', function (event) {
        var method = document.querySelector('input[name=method]:checked');
        if (method && method.value === 'all' &&
            !confirm('Update all ${allCount} data resources with a GBIF key? Each one is downloaded from GBIF in turn.')) {
            event.preventDefault();
        }
    });
</script>
</body>
</html>
