// This file is just for protoyping atm

var // interval = 10000,
    // elId = '#vis',
    // url = '/js-metrics?include=(sample-1|sample-2)',
    visName = 'bar-metrics',
    url = '/js-metrics?include=(sample-1|sample-2)',
    spec = {
        $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
        description: 'A dynamic metrics bar chart',
        data: {
            name: visName,
            url: url
        },
        /* transform: [{
                calculate: 'now()',
                as: 'time'
            },
            {
                filter: 'datum.b2 > 60'
            }
        ],*/
        mark: 'bar',
        encoding: {
            x: {
                field: 'collection',
                type: 'ordinal'
            },
            y: {
                field: 'updates',
                type: 'quantitative'
            },
            // y: {field: 'rebalances', type: 'quantitative'}
        }
    };
vegaEmbed(elId, spec).then(function (result) {
    window.setInterval(function () {
        fetch(url)
            .then(response => response.json())
            .then(data => {
                var changeSet = vega
                    .changeset()
                    .insert(data)
                    .remove(function (t) {
                        return true;
                    });
                result.view.change(visName, changeSet).run();
            });
    }, interval);
});