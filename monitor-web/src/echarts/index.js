import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, DataZoomComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, GridComponent, TooltipComponent, DataZoomComponent, CanvasRenderer])

/**
 * Build the common ECharts line-chart option used by runtime history panels.
 *
 * @param {string} name Y-axis title
 * @param {Array<*>} dataX X-axis labels
 * @returns {object} ECharts option
 */
function defaultOption(name, dataX) {
  return {
    tooltip: {
      trigger: 'axis',
      position: function (pt) {
        return [pt[0], pt[1]]
      },
      confine: true,
      padding: 3,
      backgroundColor: '#FFFFFFE0',
      textStyle: {
        fontSize: 13
      }
    },
    grid: {
      left: '10',
      right: '15',
      bottom: '0',
      top: '30',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: dataX,
      animation: false,
      axisLabel: {
        formatter: function (value) {
          value = new Date(value)
          let time = value.toLocaleTimeString()
          time = time.substring(0, time.length - 3)
          const date = [value.getDate() + 1, value.getMonth() + 1].join('/')
          return `${time}\n${date}`
        }
      }
    },
    yAxis: {
      type: 'value',
      name: name,
      boundaryGap: [0, '10%']
    },
    dataZoom: [
      {
        type: 'inside',
        start: 95,
        end: 100,
        minValueSpan: 12
      }
    ]
  }
}

/**
 * Add ECharts sampling only when the caller did not pre-sample the series.
 *
 * @param {object} series ECharts line series option
 * @param {string|null} sampling Sampling strategy, or null to disable ECharts sampling
 * @returns {object} Series option with optional sampling
 */
function withSampling(series, sampling) {
  if (sampling) {
    series.sampling = sampling
  }
  return series
}

/**
 * Attach a single line series to a chart option.
 *
 * @param {object} option ECharts option mutated in place
 * @param {string} name Series name
 * @param {Array<number>} dataY Series data
 * @param {Array<string>} colors Line and area colors
 * @param {string|null} sampling ECharts sampling strategy, or null for pre-sampled data
 */
function singleSeries(option, name, dataY, colors, sampling = 'lttb') {
  option.series = [
    withSampling(
      {
        name: name,
        type: 'line',
        showSymbol: false,
        itemStyle: {
          color: colors[0]
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            {
              offset: 0,
              color: colors[1]
            },
            {
              offset: 1,
              color: colors[2]
            }
          ])
        },
        data: dataY
      },
      sampling
    )
  ]
}

/**
 * Attach two line series to a chart option.
 *
 * @param {object} option ECharts option mutated in place
 * @param {Array<string>} name Series names
 * @param {Array<Array<number>>} dataY Series data arrays
 * @param {Array<Array<string>>} colors Line and area colors for each series
 * @param {string|null} sampling ECharts sampling strategy, or null for pre-sampled data
 */
function doubleSeries(option, name, dataY, colors, sampling = 'lttb') {
  option.series = [
    withSampling(
      {
        name: name[0],
        type: 'line',
        showSymbol: false,
        itemStyle: {
          color: colors[0][0]
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            {
              offset: 0,
              color: colors[0][1]
            },
            {
              offset: 1,
              color: colors[0][2]
            }
          ])
        },
        data: dataY[0]
      },
      sampling
    ),
    withSampling(
      {
        name: name[1],
        type: 'line',
        showSymbol: false,
        itemStyle: {
          color: colors[1][0]
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            {
              offset: 0,
              color: colors[1][1]
            },
            {
              offset: 1,
              color: colors[1][2]
            }
          ])
        },
        data: dataY[1]
      },
      sampling
    )
  ]
}

export { echarts, defaultOption, singleSeries, doubleSeries }
