import axios from "axios";
import {ElMessage} from "element-plus";
import {useStore} from "@/store";

const authItemName = "authorize"

const accessHeader = () => {
    return {
        'Authorization': `Bearer ${takeAccessToken()}`
    }
}

const defaultError = (error) => {
    console.error(error)
    ElMessage.error('发生了一些错误，请联系管理员')
}

const defaultFailure = (message, status, url) => {
    console.warn(`请求地址: ${url}, 状态码: ${status}, 错误信息: ${message}`)
    ElMessage.warning(message)
}

function takeAccessToken() {
    const str = localStorage.getItem(authItemName) || sessionStorage.getItem(authItemName);
    if(!str) return null
    const authObj = JSON.parse(str)
    if(new Date(authObj.expire) <= new Date()) {
        deleteAccessToken()
        ElMessage.warning("登录状态已过期，请重新登录！")
        return null
    }
    return authObj.token
}

function storeAccessToken(remember, token, expire){
    const authObj = {
        token: token,
        expire: expire
    }
    const str = JSON.stringify(authObj)
    if(remember)
        localStorage.setItem(authItemName, str)
    else
        sessionStorage.setItem(authItemName, str)
}

function deleteAccessToken() {
    localStorage.removeItem(authItemName)
    sessionStorage.removeItem(authItemName)
}

// GET 请求自动重试拦截器
axios.interceptors.response.use(
    response => response,
    error => {
        const config = error.config
        if (!config || config.method !== 'get') return Promise.reject(error)

        const status = error.response ? error.response.status : 0
        // 401 不重试，直接跳转登录页
        if (status === 401) return Promise.reject(error)
        // 仅对 5xx 和网络错误重试
        if (status < 500 && status !== 0) return Promise.reject(error)

        config.__retryCount = config.__retryCount || 0
        if (config.__retryCount >= 2) return Promise.reject(error)

        config.__retryCount++
        const delay = 1000 * Math.pow(2, config.__retryCount - 1)
        return new Promise(resolve => setTimeout(resolve, delay))
            .then(() => axios(config))
    }
)

function internalPost(url, data, headers, success, failure, error = defaultError){
    axios.post(url, data, { headers: headers }).then(({data}) => {
        if(data.code === 200)
            success(data.data)
        else
            failure(data.message, data.code, url)
    }).catch(err => error(err))
}

function internalGet(url, headers, success, failure, error = defaultError){
    axios.get(url, { headers: headers }).then(({data}) => {
        if(data.code === 200)
            success(data.data)
        else
            failure(data.message, data.code, url)
    }).catch(err => error(err))
}

function login(username, password, remember, success, failure = defaultFailure){
    internalPost('/api/auth/login', {
        username: username,
        password: password
    }, {
        'Content-Type': 'application/x-www-form-urlencoded'
    }, (data) => {
        storeAccessToken(remember, data.token, data.expire)
        const store=useStore()
        store.user.role=data.role
        store.user.username=data.username
        store.user.email=data.email
        ElMessage.success(`登录成功，欢迎 ${data.username} `)
        success(data)
    }, failure)
}

function post(url, data, success, failure = defaultFailure) {
    internalPost(url, data, accessHeader() , success, failure)
}

function logout(success, failure = defaultFailure){
    get('/api/auth/logout', () => {
        deleteAccessToken()
        ElMessage.success(`退出登录成功，欢迎您再次使用`)
        success()
    }, failure)
}

function get(url, success, failure = defaultFailure) {
    internalGet(url, accessHeader(), success, failure)
}

function unauthorized() {
    return !takeAccessToken()
}

export { post, get, login, logout, unauthorized }
