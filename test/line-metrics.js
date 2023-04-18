// This file is just for protoyping atm

var // interval = 10000,
    // elId = '#vis',
    // url = '/js-metrics?include=(sample-1|sample-2)',
    visName = 'line-metrics',
    samples = 30,
    yField = 'updates',
    spec = {
        $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
        description: 'A dynamic line chart',
        data: {
            name: visName,
            url: url
        },
        mark: 'line',
        encoding: {
            x: {
                field: 'time',
                timeUnit: 'hoursminutesseconds'
            },
            y: {
                field: yField,
                type: 'quantitative'
            },
            color: {
                field: 'collection',
                type: 'nominal'
            }
        }
    };
vegaEmbed(elId, spec).then(function (result) {
    window.setInterval(function () {
        fetch(url)
            .then(response => response.json())
            .then(data => {
                var currentData = result.view.data(visName),
                    timesCnt = currentData.length / data.length,
                    removeTime = timesCnt == samples ? currentData[0].time : 0,
                    changeSet = vega
                    .changeset()
                    .insert(data)
                    .remove(function (t) {
                        return t.time <= removeTime;
                    });
                result.view.change(visName, changeSet).run();
            });
    }, interval);
})