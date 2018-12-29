# -*- coding:utf-8 -*-

import sqlite3

"""
# 数据库处理类 没有调通，暂时放弃
class DataBase(object):
    def __init__(self, Name):
        self.DatabaseName = Name
        self.con = sqlite3.connect(self.DatabadeName)
        self.cu = self.con.cursor()
        self.data = None
        
    def Execute(self, QueryCommand):
        self.cu.execute(QueryCommand)
        
    def GetResult(self):
        self.data = cu.fetchall()
        return self.data
        
    def __del__(self):
        self.cu.close()
        self.con.close()
"""
        
        
# 建立数据库连接
def CreateConnection(DatabaseName):
    con = sqlite3.connect(DatabaseName)
    return con
        
# 建立游标
def CreateCursor(con):
    cu = con.cursor()
    return cu
    
# 执行查询
def RunQuery(cu, QueryCommand):
    cu.execute(QueryCommand)
    data = cu.fetchall()
    return data
    
# 将数据写入csv文件
import csv
def WriteCsv(csvName, cu, data):
    with open(csvName, "w") as csvfile:
        writer = csv.writer(csvfile)
        item = []
        for i in range(4):
            item.append(cu.description[i][0])
        writer.writerow(item)
        writer.writerows(data)
        
      
    